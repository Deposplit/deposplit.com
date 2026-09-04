/*
 * The MIT License
 *
 * Copyright (c) 2026 Squeng AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package driving_adapters

import driven_ports.ContactRepository
import driven_ports.KeyConflictRepository
import driven_ports.RetainedDepositRepository
import driven_ports.SecretRepository
import driven_ports.ShareMetadataRepository
import driven_ports.ShareRelay
import driven_ports.ShareRelayResolver
import driven_ports.ShareRepository
import driving_adapters.ShareEncryption
import driving_ports.ContactManagement
import driving_ports.Identity
import driving_ports.ShareManagement
import jakarta.inject.Inject
import shamir.SecretSharing
import value_objects.svo.CipherSuite
import value_objects.svo.Contact
import value_objects.svo.CustodyHeartbeatTuning
import value_objects.svo.HeldShare
import value_objects.svo.KeyConflict
import value_objects.svo.MimeType
import value_objects.svo.PayloadCanonical
import value_objects.svo.ReconstructionIntegrity
import value_objects.svo.ReconstructionResult
import value_objects.svo.RegenerateIdentityResult
import value_objects.svo.RetainedDepositBlob
import value_objects.svo.Role
import value_objects.svo.Secret
import value_objects.svo.SecretLimits
import value_objects.svo.SecretState
import value_objects.svo.SecretTooLargeException
import value_objects.svo.ShareMetadata
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareTransactionType
import value_objects.svo.SignatureVerificationException
import value_objects.svo.VerificationLevel

import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.util.Try

class ShareService @Inject() (
    relayResolver: ShareRelayResolver,
    encryption: ShareEncryption,
    shareRepository: ShareRepository,
    shareMetadataRepository: ShareMetadataRepository,
    secretRepository: SecretRepository,
    contactRepository: ContactRepository,
    contactManagement: ContactManagement,
    keyConflictRepository: KeyConflictRepository,
    retainedDepositRepository: RetainedDepositRepository,
    identity: Identity
) extends ShareManagement:

  // ── Relay resolution ──────────────────────────────────────────────────────

  /** Every distinct relay referenced across the contact list, plus the default — used by fan-out methods (syncInbox,
    * listPendingRequests, syncDistributed, listSentRequests) since a device has no other way to know in advance which
    * relay a given contact's pending item lives on. Deduped by URL, not per-contact; each relay call is independently
    * soft-failed so one unreachable BYOR relay doesn't blank out results from the default relay or others.
    */
  private def allRelays(): List[ShareRelay] =
    (contactRepository.getAll().map(_.relayBaseUrl) :+ None).distinct.map(relayResolver.resolve)

  private def relayForContact(contact: Contact): ShareRelay = relayResolver.resolve(contact.relayBaseUrl)

  /** Finds a row by id across every known relay — the caller (UI) has no relay context for a bare requestId, only the
    * fan-out list already used to discover it. Returns the relay it was found on too, so the caller can act on it
    * through the *same* relay rather than re-resolving (which could point elsewhere if a contact's relayBaseUrl changed
    * since the row was created).
    */
  private def findShareRequest(requestId: UUID): (ShareRelay, ShareRequest) =
    allRelays().iterator
      .map(relay => Try((relay, relay.getShareRequest(requestId))))
      .collectFirst { case scala.util.Success(pair) => pair }
      .getOrElse(throw IllegalStateException(s"Share request $requestId not found on any known relay"))

  // ── Signature helpers ────────────────────────────────────────────────────

  private def verifyOpen(req: ShareRequest): Boolean =
    contactRepository.getByVerifyKey(req.senderKey).exists { contact =>
      val canon = PayloadCanonical.forOpen(
        req.secretId,
        req.transactionType,
        req.recipientKey,
        req.label,
        req.secretCreatedAt,
        req.ciphertext,
        req.k,
        req.n,
        req.mimeType
      )
      identity.verify(canon, req.senderSignature, contact.verifyKey)
    }

  private def verifyRespond(req: ShareRequest): Boolean =
    req.recipientSignature.exists { sig =>
      contactRepository.getByVerifyKey(req.recipientKey).exists { contact =>
        val approved = req.state == ShareRequestState.Approved
        val signedCiphertext =
          if approved && req.transactionType == ShareTransactionType.Retrieval then req.ciphertext else None
        val canon = PayloadCanonical.forRespond(req.id, approved, signedCiphertext)
        identity.verify(canon, sig, contact.verifyKey)
      }
    }

  // ── Sender flows ──────────────────────────────────────────────────────────

  override def deposit(
      secret: Array[Byte],
      label: String,
      contacts: List[Contact],
      threshold: Int,
      mimeType: MimeType = MimeType.Default
  ): Unit =
    if secret.length > SecretLimits.MaxSecretBytes then
      throw SecretTooLargeException(secret.length, SecretLimits.MaxSecretBytes)
    val shares = SecretSharing.split(secret, contacts.size, threshold)
    val secretId = UUID.randomUUID()
    val createdAt = Instant.now()
    shares.zip(contacts).foreach { (share, contact) =>
      val ct = encryption.encrypt(share, contact.encKey)
      val canon =
        PayloadCanonical.forOpen(
          secretId,
          ShareTransactionType.Deposit,
          contact.verifyKey,
          label,
          createdAt,
          Some(ct),
          Some(threshold),
          Some(contacts.size),
          Some(mimeType)
        )
      val senderSignature = identity.sign(canon)
      val req = relayForContact(contact).openShareRequest(
        secretId,
        contact.verifyKey,
        label,
        createdAt,
        ShareTransactionType.Deposit,
        Some(ct),
        k = Some(threshold),
        n = Some(contacts.size),
        mimeType = Some(mimeType),
        senderSignature = senderSignature
      )
      shareMetadataRepository.save(ShareMetadata(req.id, secretId, contact.id))
      // Retained until this holder's pickup is confirmed (relay-observed or
      // heartbeat-attested), then discarded. Safe to retain: this blob is encrypted to the
      // holder's X25519 key, so this device cannot decrypt it itself.
      Try(
        retainedDepositRepository
          .save(
            RetainedDepositBlob(req.id, secretId, contact.id, label, createdAt, ct, threshold, contacts.size, mimeType)
          )
      )
    }
    secretRepository.save(Secret(secretId, label, mimeType, threshold, contacts.size, createdAt, SecretState.Active))

  override def listSecrets(): List[Secret] = secretRepository.getAll()

  override def syncDistributed(): Unit =
    val existingMetadata = shareMetadataRepository.getAll()
    allRelays().foreach { relay =>
      Try(relay.listShareRequests(Role.Sender, Some(ShareTransactionType.Deposit)))
        .getOrElse(Nil)
        .foreach { req =>
          if req.state == ShareRequestState.Withdrawn then
            // Best-effort tombstone: the holder unilaterally stopped holding this
            // share. Drop the local pointer so the health count reflects it, then clean up the
            // relay row — it has served its purpose and needn't linger. Row *absence* is never
            // itself a signal; only an *observed* withdrawn state counts, and we've just
            // observed it.
            Try(shareMetadataRepository.delete(req.id))
            Try(relay.deleteShareRequest(req.id))
          else
            // A row for a holder we no longer have a contact record for can't be re-anchored to
            // a contactId — skip rather than drop the holder's identity on the floor.
            contactRepository.getByVerifyKey(req.recipientKey).foreach { contact =>
              val priorConfirmedAt = existingMetadata.find(_.id == req.id).flatMap(_.lastConfirmedAt)
              if req.state == ShareRequestState.Approved && isRetentionStillPending(req.id) then
                // First-observed pickup confirmation (relay-observed channel): a
                // one-time transition, not "still approved therefore still fresh" — an
                // unchanging Approved row on a later poll must not keep bumping freshness, or a
                // long-dead holder would look perpetually confirmed. The retained blob's
                // continued existence is exactly the "not yet confirmed by any channel" marker,
                // so its presence is what gates the stamp.
                shareMetadataRepository.save(ShareMetadata(req.id, req.secretId, contact.id, Some(Instant.now())))
                Try(retainedDepositRepository.delete(req.id))
              else shareMetadataRepository.save(ShareMetadata(req.id, req.secretId, contact.id, priorConfirmedAt))
            }
        }
      // A retrieve approval is also proof-of-custody. Polled here purely for that
      // freshness side effect; the functional read path for these rows is reconstruct()/
      // listSentRequests(), unchanged.
      Try(relay.listShareRequests(Role.Sender, Some(ShareTransactionType.Retrieval), Some(ShareRequestState.Approved)))
        .getOrElse(Nil)
        .foreach { req =>
          // Matched on secretId plus the holder's key, the same pair requestAll fans out on —
          // the row itself carries no pointer back to this device's records, and needs none.
          contactRepository.getByVerifyKey(req.recipientKey).foreach { contact =>
            existingMetadata.find(m => m.secretId == req.secretId && m.contactId == contact.id).foreach { meta =>
              Try(shareMetadataRepository.save(meta.copy(lastConfirmedAt = Some(Instant.now()))))
            }
          }
        }
    }
    reconcileDiscarding()
    processHeartbeats()

  private def isRetentionStillPending(depositId: UUID): Boolean =
    Try(retainedDepositRepository.getAll()).getOrElse(Nil).exists(_.id == depositId)

  /** For every Discarding `Secret`, checks whether each remaining holder's fanned-out removal request has been
    * approved; approved ones are cleaned up (relay row deleted, local `ShareMetadata` removed). Once a Discarding
    * secret has no `ShareMetadata` rows left, its `Secret` record itself is removed — the Active/Discarding two-state
    * lifecycle.
    */
  private def reconcileDiscarding(): Unit =
    val discarding = secretRepository.getAll().filter(_.state == SecretState.Discarding)
    if discarding.nonEmpty then
      val discardingIds = discarding.map(_.id).toSet
      val removalRequests: List[(ShareRelay, ShareRequest)] = allRelays().flatMap { relay =>
        Try(relay.listShareRequests(Role.Sender, Some(ShareTransactionType.Removal)))
          .getOrElse(Nil)
          .filter(r => discardingIds.contains(r.secretId))
          .map(relay -> _)
      }
      discarding.foreach { secret =>
        val metasForSecret = shareMetadataRepository.getAll().filter(_.secretId == secret.id)
        metasForSecret.foreach { meta =>
          contactRepository.getById(meta.contactId).foreach { contact =>
            removalRequests
              .find { case (_, r) =>
                r.secretId == meta.secretId && r.recipientKey.sameElements(contact.verifyKey) &&
                r.state == ShareRequestState.Approved
              }
              .foreach { case (relay, _) =>
                Try(relay.deleteShareRequest(meta.id))
                Try(shareMetadataRepository.delete(meta.id))
              }
          }
        }
        val remaining = shareMetadataRepository.getAll().filter(_.secretId == secret.id)
        if remaining.isEmpty then Try(secretRepository.delete(secret.id))
      }

  override def listDistributed(): List[ShareMetadata] = shareMetadataRepository.getAll()

  override def listSentRequests(): List[ShareRequest] =
    allRelays()
      .flatMap(relay => Try(relay.listShareRequests(Role.Sender)).getOrElse(Nil))
      .filterNot(_.transactionType == ShareTransactionType.Deposit)

  // A holder is worth prioritizing for a fresh retrieval ask when the custody-freshness rule
  // that decides "still counts toward n_live" already trusts them: an unexpired
  // proof-of-custody and no standing opt-out. Recomputed here (not shared with the app layer's
  // own freshness display logic) — a small, deliberate duplication of a threshold check rather
  // than restructuring already-shipped display code.
  private def isConfirmed(meta: ShareMetadata): Boolean =
    contactRepository.getById(meta.contactId).exists { contact =>
      contact.heartbeatOptedOutAt.isEmpty &&
      meta.lastConfirmedAt
        .exists(t => Duration.between(t, Instant.now()).compareTo(CustodyHeartbeatTuning.lossThreshold) <= 0)
    }

  override def requestAll(secretId: UUID): Unit =
    secretRepository.getAll().find(_.id == secretId).foreach { secret =>
      val deposited = shareMetadataRepository.getAll().filter(_.secretId == secretId)
      val existing = allRelays().flatMap(relay =>
        Try(relay.listShareRequests(Role.Sender, Some(ShareTransactionType.Retrieval))).getOrElse(Nil)
      )
      // Fan out to the health-informed fresh set first; widen to everyone only when
      // there aren't enough confirmed holders to reach k. A retrieval request exists solely to
      // feed an eventual reconstruct(), so this targeting applies here rather than as a
      // separate method.
      val confirmed = deposited.filter(isConfirmed)
      val targets = if confirmed.size >= secret.k then confirmed else deposited
      targets.foreach { meta =>
        contactRepository.getById(meta.contactId).foreach { contact =>
          // Matched on secretId plus the holder's key. Not secretId alone — every holder of a
          // secret shares it, so one standing row would silence the whole fan-out. A holder who
          // rotated keys since the row was opened no longer matches, which is right: that row is
          // unreachable under the new key anyway.
          val hasActive = existing.exists(r =>
            r.secretId == meta.secretId &&
              r.recipientKey.sameElements(contact.verifyKey) &&
              (r.state == ShareRequestState.Pending || r.state == ShareRequestState.Approved)
          )
          if !hasActive then
            Try {
              val canon = PayloadCanonical.forOpen(
                meta.secretId,
                ShareTransactionType.Retrieval,
                contact.verifyKey,
                secret.label,
                secret.secretCreatedAt,
                None
              )
              val senderSignature = identity.sign(canon)
              relayForContact(contact).openShareRequest(
                meta.secretId,
                contact.verifyKey,
                secret.label,
                secret.secretCreatedAt,
                ShareTransactionType.Retrieval,
                None,
                senderSignature = senderSignature
              )
            }
        }
      }
    }

  override def openRequest(shareId: UUID, transactionType: ShareTransactionType): ShareRequest =
    val meta = shareMetadataRepository
      .getAll()
      .find(_.id == shareId)
      .getOrElse(throw IllegalArgumentException(s"No local share record for id $shareId"))
    val secret = secretRepository
      .getAll()
      .find(_.id == meta.secretId)
      .getOrElse(throw IllegalStateException(s"No local record for secret ${meta.secretId}"))
    val contact = contactRepository
      .getById(meta.contactId)
      .getOrElse(throw IllegalStateException(s"Contact not found for id ${meta.contactId}"))
    val canon = PayloadCanonical.forOpen(
      meta.secretId,
      transactionType,
      contact.verifyKey,
      secret.label,
      secret.secretCreatedAt,
      None
    )
    val senderSignature = identity.sign(canon)
    relayForContact(contact).openShareRequest(
      meta.secretId,
      contact.verifyKey,
      secret.label,
      secret.secretCreatedAt,
      transactionType,
      None,
      senderSignature = senderSignature
    )

  /** Pure read: collects and decrypts k approved retrieval shares, but never tears down local `ShareMetadata` or relay
    * rows. Use `discardSecret` for teardown — reconstruct is now a *step* toward a possible re-split, not an implicit
    * "I'm done with this" signal.
    */
  override def reconstruct(secretId: UUID): ReconstructionResult =
    val secret = secretRepository
      .getAll()
      .find(_.id == secretId)
      .getOrElse(throw IllegalStateException(s"No local record for secret $secretId"))
    val allRequests: List[(ShareRelay, ShareRequest)] =
      allRelays().flatMap(relay =>
        Try(relay.listShareRequests(Role.Sender, Some(ShareTransactionType.Retrieval))).getOrElse(Nil).map(relay -> _)
      )
    // An unverified recipientSignature is treated as "not yet approved" rather than a hard
    // error — a forged approval simply doesn't count toward the threshold.
    val approved = allRequests.filter { case (_, r) =>
      r.secretId == secretId &&
      r.state == ShareRequestState.Approved &&
      r.ciphertext.isDefined &&
      verifyRespond(r)
    }
    require(approved.size >= secret.k, s"Need at least ${secret.k} approved shares (have ${approved.size})")
    val contacts = contactRepository.getAll()
    // Each decrypted share is kept paired with its originating contact so an excluded
    // index (from combineWithIntegrity) reports back as a suspect contact, not a meaningless
    // array position.
    val decryptedWithContact = approved.map { case (_, req) =>
      val contact = contacts
        .find(_.verifyKey.sameElements(req.recipientKey))
        .getOrElse(throw IllegalStateException(s"Contact not found for recipient key"))
      (encryption.decrypt(req.ciphertext.get, contact.encKey), contact.id)
    }
    val result = SecretSharing.combineWithIntegrity(decryptedWithContact.map(_._1), secret.k)
    val integrity =
      if !result.hasIntegrityMargin then ReconstructionIntegrity.NoMargin
      else if result.excludedIndices.isEmpty then ReconstructionIntegrity.Confirmed
      else ReconstructionIntegrity.ExcludedSuspects(result.excludedIndices.map(decryptedWithContact(_)._2))
    ReconstructionResult(result.secret, integrity, secret.mimeType)

  /** Fans out a sender-initiated removal to every known holder of secretId and flips the Secret to Discarding
    * immediately, before any holder has responded.
    */
  override def discardSecret(secretId: UUID): Unit =
    val secret = secretRepository
      .getAll()
      .find(_.id == secretId)
      .getOrElse(throw IllegalStateException(s"No local record for secret $secretId"))
    secretRepository.save(secret.copy(state = SecretState.Discarding))
    shareMetadataRepository
      .getAll()
      .filter(_.secretId == secretId)
      .foreach(share => Try(openRequest(share.id, ShareTransactionType.Removal)))

  /** Local-only teardown for a Discarding secret whose holders won't all respond (e.g. a permanently dark holder) —
    * removes the Secret and its remaining `ShareMetadata` rows without waiting for relay confirmation.
    */
  override def forceForgetSecret(secretId: UUID): Unit =
    shareMetadataRepository
      .getAll()
      .filter(_.secretId == secretId)
      .foreach(share => Try(shareMetadataRepository.delete(share.id)))
    secretRepository.delete(secretId)

  // ── Recipient flows ───────────────────────────────────────────────────────

  override def syncInbox(): Unit =
    allRelays().foreach { relay =>
      val pending =
        Try(
          relay.listShareRequests(Role.Recipient, Some(ShareTransactionType.Deposit), Some(ShareRequestState.Pending))
        )
          .getOrElse(Nil)
      // Unknown sender or unverified senderSignature: skip silently, do not auto-approve.
      pending.filter(verifyOpen).foreach { req =>
        contactRepository.getByVerifyKey(req.senderKey).foreach { senderContact =>
          noteRelinked(senderContact)
          // A deposit without valid k/n/mimeType can't happen against a conforming relay (all three
          // required by ShareRequestsService) — skip defensively rather than store a share we can't
          // later report thresholds for during recovery.
          (req.k, req.n, req.mimeType) match
            case (Some(k), Some(n), Some(mimeType)) =>
              // Order is load-bearing: approving is what clears the relay's only copy of the
              // ciphertext, so it is the last step of pickup and never the first. Decrypting and
              // storing first means a failure leaves the row pending with the relay's copy intact,
              // and the next poll simply retries. The ciphertext is already in hand and already
              // authenticated — verifyOpen above covers it — so the approval response's echoed copy
              // adds nothing. Try isolates one bad row from the rest of the poll; it must never span
              // a half-completed pickup.
              Try {
                val alreadyHeld = shareRepository.getPlaintextShare(req.secretId).isDefined
                val stored =
                  alreadyHeld || req.ciphertext.fold(false) { ct =>
                    val plaintext = encryption.decrypt(ct, senderContact.encKey)
                    shareRepository.save(
                      HeldShare(
                        id = req.id,
                        secretId = req.secretId,
                        label = req.label,
                        contactId = senderContact.id,
                        createdAt = req.secretCreatedAt,
                        pickedUpAt = Instant.now(),
                        plaintextShare = plaintext,
                        k = k,
                        n = n,
                        mimeType = mimeType
                      )
                    )
                    true
                  }
                // Sent even when an earlier poll already stored the share but failed to acknowledge
                // it: without the approval the sender never observes the pickup and keeps her
                // retained blob forever.
                if stored then
                  val canon = PayloadCanonical.forRespond(req.id, approved = true, ciphertext = None)
                  relay.respondToShareRequest(req.id, approved = true, recipientSignature = identity.sign(canon))
              }
            case _ => ()
        }
      }
    }
    processRecoveryMetadata()
    processRotations()
    emitHeartbeats()

  // Holder side — opportunistically piggybacks this same inbox poll: for each distinct
  // sender this device currently holds at least one share from, pushes one coalesced heartbeat
  // (or opt-out notice) once the per-sender emission interval has elapsed. Each push is
  // independently best-effort so one unreachable BYOR relay doesn't block heartbeating other
  // senders. lastHeartbeatSentAt only advances on a *successful* push, so a transient failure
  // retries on the very next poll rather than waiting out the full interval again.
  private def emitHeartbeats(): Unit =
    val held = shareRepository.getAll()
    val senderIds = held.map(_.contactId).toSet
    val now = Instant.now()
    senderIds.foreach { contactId =>
      contactRepository.getById(contactId).foreach { contact =>
        val isDue = contact.lastHeartbeatSentAt.forall { sent =>
          Duration.between(sent, now).compareTo(CustodyHeartbeatTuning.emissionInterval) >= 0
        }
        if isDue then
          val secretIds =
            if contact.heartbeatEmissionOptedOut then Nil else held.filter(_.contactId == contactId).map(_.secretId)
          val canon = PayloadCanonical.forHeartbeat(contact.verifyKey, secretIds, contact.heartbeatEmissionOptedOut)
          Try(identity.sign(canon)).toOption.foreach { signature =>
            val pushed = Try(
              relayForContact(contact)
                .pushHeartbeat(contact.verifyKey, secretIds, contact.heartbeatEmissionOptedOut, signature)
            ).isSuccess
            if pushed then contactRepository.save(contact.copy(lastHeartbeatSentAt = Some(now)))
          }
      }
    }

  // Owner side — auto-verifies each holder's latest heartbeat (or opt-out notice)
  // against a known contact's trusted key, then updates local freshness/opt-out state. Never
  // deletes a heartbeat row — see CustodyHeartbeat for why it's a standing status, not a one-shot
  // delivery. Unknown senders and forged signatures are silently skipped, same posture as
  // processRotations().
  // Anything arriving from a contact proves they hold this device's current key, because the relay only ever returns
  // rows addressed to the caller — so every inbound path notes it, and a contact who has relinked drops off the
  // awaiting-relink list without anyone tapping anything. Rows this device created itself, which syncDistributed reads
  // back, prove nothing about the contact and are deliberately not counted. Never allowed to break a sync pass.
  private def noteRelinked(contact: Contact): Unit =
    Try(contactManagement.markRelinked(contact.id))
    ()

  private def processHeartbeats(): Unit =
    // Nothing here can be verified without our own key, so a device whose key storage is locked does nothing and
    // picks this up on a later pass rather than failing every notice.
    identity.verifyKey().foreach { myKey =>
      val existingMetadata = shareMetadataRepository.getAll()
      allRelays().foreach { relay =>
        val notices = Try(relay.listHeartbeats()).getOrElse(Nil)
        notices.foreach { notice =>
          contactRepository.getByVerifyKey(notice.holderKey).foreach { contact =>
            noteRelinked(contact)
            val canon = PayloadCanonical.forHeartbeat(myKey, notice.secretIds, notice.optedOut)
            if identity.verify(canon, notice.signature, notice.holderKey) then
              if notice.optedOut then
                Try(contactRepository.save(contact.copy(heartbeatOptedOutAt = Some(notice.createdAt))))
              else
                if contact.heartbeatOptedOutAt.isDefined then
                  Try(contactRepository.save(contact.copy(heartbeatOptedOutAt = None)))
                notice.secretIds.foreach { secretId =>
                  existingMetadata.find(m => m.secretId == secretId && m.contactId == contact.id).foreach { meta =>
                    Try(shareMetadataRepository.save(meta.copy(lastConfirmedAt = Some(notice.createdAt))))
                    if isRetentionStillPending(meta.id) then Try(retainedDepositRepository.delete(meta.id))
                  }
                }
          }
        }
      }
    }

  override def setHeartbeatEmissionOptedOut(contactId: UUID, optedOut: Boolean): Unit =
    val contact = contactRepository
      .getById(contactId)
      .getOrElse(throw IllegalStateException(s"Contact not found for id $contactId"))
    // Reset so the changed preference reaches the contact on the very next poll rather than
    // waiting out the emission interval.
    contactRepository.save(contact.copy(heartbeatEmissionOptedOut = optedOut, lastHeartbeatSentAt = None))

  /** Receiving side — auto-verifies a signed rotation notice against the trusted old key already on file for a known
    * contact, downgrades the verification level to at most Low (a signed rotation proves continuity of key control, not
    * a fresh personhood check, so it can never carry a higher level forward), and updates the contact record in place,
    * preserving contactId. Unknown senders and forged/mismatched signatures are silently skipped — a stranger's notice
    * must never mutate a real contact.
    */
  private def processRotations(): Unit =
    allRelays().foreach { relay =>
      val notices = Try(relay.listRotations()).getOrElse(Nil)
      notices.foreach { notice =>
        contactRepository.getByVerifyKey(notice.oldVerifyKey).foreach { contact =>
          noteRelinked(contact)
          val canon = PayloadCanonical.forRotation(
            notice.recipientKey,
            notice.newVerifyKey,
            notice.newEncKey,
            notice.newCipherSuite
          )
          if identity.verify(canon, notice.signature, notice.oldVerifyKey) then
            // A rotation claiming continuity from a key the user has flagged
            // compromised is never auto-accepted. Capture a durable local KeyConflict record
            // *before* touching the relay notice: the relay may lose its state at any time and
            // must never be relied on to keep the alert alive. Skip updateContact entirely — the
            // contact record is left untouched; only a fresh human-verified relink can move it
            // forward.
            if contact.revokedVerifyKeys.exists(_.sameElements(notice.oldVerifyKey)) then
              Try(
                keyConflictRepository.save(
                  KeyConflict(
                    id = UUID.randomUUID(),
                    contactId = contact.id,
                    oldVerifyKey = notice.oldVerifyKey,
                    newVerifyKey = notice.newVerifyKey,
                    newEncKey = notice.newEncKey,
                    detectedAt = Instant.now()
                  )
                )
              )
              Try(relay.deleteRotation(notice.id))
            else
              // A cipher-suite-only change goes through the same downgrade as a plain
              // key rotation: an algorithm change is still continuity of key control, not a
              // fresh personhood check.
              val downgraded = if contact.verificationLevel < VerificationLevel.Low then contact.verificationLevel
              else VerificationLevel.Low
              Try(
                contactManagement.updateContact(
                  contact.id,
                  Some(notice.newVerifyKey),
                  Some(notice.newEncKey),
                  Some(notice.newCipherSuite),
                  Some(downgraded)
                )
              )
              Try(relay.deleteRotation(notice.id))
        }
      }
    }

  /** Sending side (client primitive only — see ShareManagement.pushRotation). Signs the new keys with the device's
    * *current* identity, which becomes oldVerifyKey on the wire, proving continuity of key control to the recipient.
    */
  override def pushRotation(
      contactId: UUID,
      newVerifyKey: Array[Byte],
      newEncKey: Array[Byte],
      newCipherSuite: CipherSuite
  ): Unit =
    val contact = contactRepository
      .getById(contactId)
      .getOrElse(throw IllegalStateException(s"Contact not found for id $contactId"))
    val canon = PayloadCanonical.forRotation(contact.verifyKey, newVerifyKey, newEncKey, newCipherSuite)
    val signature = identity.sign(canon)
    relayForContact(contact).pushRotation(contact.verifyKey, newVerifyKey, newEncKey, newCipherSuite, signature)

  /** Whether every relay this device knows of answered. `syncInbox` and `syncDistributed` soft-fail per relay on
    * purpose — one dark BYOR relay must not blank out results from the others — which also means neither can tell its
    * caller that a relay went unheard. Rotation is the one caller that needs to know, because it is about to retire the
    * identity those rows are addressed to, so it asks separately rather than the fan-out growing a return value every
    * other caller would ignore.
    */
  private def allRelaysAnswered(): Boolean =
    allRelays().forall { relay =>
      Try(
        relay.listShareRequests(Role.Recipient, Some(ShareTransactionType.Deposit), Some(ShareRequestState.Pending))
      ).isSuccess
    }

  /** The identity-regeneration trigger. Order matters: the drain and the rotation pushes must both happen before
    * activateKeyPair, since pushRotation (and the drain's own relay calls) sign with whatever identity is currently
    * persisted — that's what proves continuity from the old key to each contact. If the app dies partway through, the
    * old identity is still active (nothing was persisted yet), so a retry simply regenerates and re-pushes from
    * scratch; any contact who received an orphaned first attempt auto-corrects on the next successful push, per the
    * existing K_old-signed auto-accept rule.
    */
  override def regenerateIdentity(): RegenerateIdentityResult =
    Try(syncInbox())
    Try(syncDistributed())
    val drainSucceeded = allRelaysAnswered()
    val newKeys = identity.generateNewKeyPair()
    val contacts = contactRepository.getAll()
    val notified = contacts.count { contact =>
      Try(pushRotation(contact.id, newKeys.verifyKey, newKeys.encKey, CipherSuite.current)).isSuccess
    }
    identity.activateKeyPair(newKeys)
    RegenerateIdentityResult(notified, contacts.size, drainSucceeded)

  /** Identity recovery — sender/owner side. Consumes pending recoveryMetadata pushes addressed to this device,
    * rebuilding Secret/ShareMetadata records from what each holder reports. A push is trusted only once its
    * senderSignature verifies against a *known* contact — the holder must already have been re-added out-of-band before
    * their push is honored. Consumed rows are deleted from the relay once processed.
    */
  private def processRecoveryMetadata(): Unit =
    allRelays().foreach { relay =>
      val pushes =
        Try(
          relay.listShareRequests(
            Role.Recipient,
            Some(ShareTransactionType.Inventory),
            Some(ShareRequestState.Approved)
          )
        )
          .getOrElse(Nil)
      pushes.filter(verifyOpen).foreach { req =>
        contactRepository.getByVerifyKey(req.senderKey).foreach { holderContact =>
          noteRelinked(holderContact)
          (req.k, req.n, req.mimeType) match
            case (Some(k), Some(n), Some(mimeType)) =>
              if secretRepository.getAll().forall(_.id != req.secretId) then
                Try(
                  secretRepository.save(
                    Secret(req.secretId, req.label, mimeType, k, n, req.secretCreatedAt, SecretState.Active)
                  )
                )
              if shareMetadataRepository
                  .getAll()
                  .forall(m => !(m.secretId == req.secretId && m.contactId == holderContact.id))
              then Try(shareMetadataRepository.save(ShareMetadata(UUID.randomUUID(), req.secretId, holderContact.id)))
              Try(relay.deleteShareRequest(req.id))
            case _ => ()
        }
      }
    }

  override def pushRecoveryMetadata(contactId: UUID): Unit =
    val contact = contactRepository
      .getById(contactId)
      .getOrElse(throw IllegalStateException(s"Contact not found for id $contactId"))
    shareRepository.getAll().filter(_.contactId == contactId).foreach { share =>
      Try {
        val canon = PayloadCanonical.forOpen(
          share.secretId,
          ShareTransactionType.Inventory,
          contact.verifyKey,
          share.label,
          share.createdAt,
          None,
          Some(share.k),
          Some(share.n),
          Some(share.mimeType)
        )
        val senderSignature = identity.sign(canon)
        relayForContact(contact).openShareRequest(
          share.secretId,
          contact.verifyKey,
          share.label,
          share.createdAt,
          ShareTransactionType.Inventory,
          None,
          k = Some(share.k),
          n = Some(share.n),
          mimeType = Some(share.mimeType),
          senderSignature = senderSignature
        )
      }
    }

  override def listHeld(): List[HeldShare] = shareRepository.getAll()

  override def listPendingRequests(): List[ShareRequest] =
    allRelays()
      .flatMap(relay =>
        Try(relay.listShareRequests(Role.Recipient, state = Some(ShareRequestState.Pending))).getOrElse(Nil)
      )
      .filterNot(_.transactionType == ShareTransactionType.Deposit)
      // A forged removal/retrieval request has no AEAD backstop — must never reach the UI.
      .filter(verifyOpen)

  override def respond(requestId: UUID, approved: Boolean): Unit =
    val (relay, request) = findShareRequest(requestId)
    if !verifyOpen(request) then
      throw SignatureVerificationException(s"senderSignature does not verify for request $requestId")
    val ciphertext =
      if approved && request.transactionType == ShareTransactionType.Retrieval then
        // Matched on secretId: the request names the secret, and a holder keeps one share per
        // secret per sender.
        val plaintext = shareRepository
          .getPlaintextShare(request.secretId)
          .getOrElse(throw IllegalStateException(s"Share for secret ${request.secretId} not in local storage"))
        // Re-encrypt to the requester's *current* X25519 key — looked up live, not pinned at
        // deposit time. This is what lets reconstruction survive a sender key rotation/recovery
        // — the core reason the holder decrypts at pickup.
        val requesterContact = contactRepository
          .getByVerifyKey(request.senderKey)
          .getOrElse(throw IllegalStateException(s"Contact not found for requester"))
        Some(encryption.encrypt(plaintext, requesterContact.encKey))
      else None
    val canon = PayloadCanonical.forRespond(requestId, approved, ciphertext)
    val recipientSignature = identity.sign(canon)
    relay.respondToShareRequest(requestId, approved, ciphertext, recipientSignature)
    if approved && request.transactionType == ShareTransactionType.Removal then
      shareRepository.getAll().find(_.secretId == request.secretId).foreach(h => shareRepository.delete(h.id))

  /** Unilateral, no approval needed — but not purely silent: best-effort notifies the sender via a withdraw tombstone
    * before the local record is dropped. The relay call is fire-and-forget; local deletion always proceeds regardless
    * of its outcome.
    */
  override def deleteHeldShare(shareId: UUID): Unit =
    shareRepository.getAll().find(_.id == shareId).foreach { share =>
      contactRepository.getById(share.contactId).foreach { senderContact =>
        Try(relayForContact(senderContact).withdrawShareRequests(secretId = Some(share.secretId)))
      }
    }
    shareRepository.delete(shareId)

  /** Same best-effort withdraw-tombstone courtesy as `deleteHeldShare`, but scoped to every share from `contactId` in
    * one relay call (senderKey) rather than one per secretId.
    */
  override def deleteAllHeldFromSender(contactId: UUID): Unit =
    contactRepository.getById(contactId).foreach { senderContact =>
      Try(relayForContact(senderContact).withdrawShareRequests(senderKey = Some(senderContact.verifyKey)))
    }
    shareRepository
      .getAll()
      .filter(_.contactId == contactId)
      .foreach(share => shareRepository.delete(share.id))

  // ── Key conflicts (never auto-resolved) ──────────────────────────────────────

  override def listKeyConflicts(): List[KeyConflict] = keyConflictRepository.getAll()

  override def dismissKeyConflict(id: UUID): Unit = keyConflictRepository.delete(id)
