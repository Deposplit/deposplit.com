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
import driven_ports.SecretRepository
import driven_ports.ShareMetadataRepository
import driven_ports.ShareRelay
import driven_ports.ShareRelayResolver
import driven_ports.ShareRepository
import driving_adapters.ShareEncryption
import driving_ports.Identity
import driving_ports.ShareManagement
import jakarta.inject.Inject
import shamir.SecretSharing
import value_objects.svo.Contact
import value_objects.svo.HeldShare
import value_objects.svo.PayloadCanonical
import value_objects.svo.Role
import value_objects.svo.Secret
import value_objects.svo.SecretState
import value_objects.svo.ShareMetadata
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareRequestType
import value_objects.svo.SignatureVerificationException

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
    identity: Identity
) extends ShareManagement:

  // ── Relay resolution ──────────────────────────────────────────────────────

  /** Every distinct relay referenced across the contact list, plus the default — used by
    * fan-out methods (syncInbox, listPendingRequests, syncDistributed, listSentRequests) since
    * a device has no other way to know in advance which relay a given contact's pending item
    * lives on. Deduped by URL, not per-contact; each relay call is independently soft-failed so
    * one unreachable BYOR relay doesn't blank out results from the default relay or others.
    */
  private def allRelays(): List[ShareRelay] =
    (contactRepository.getAll().map(_.relayBaseUrl) :+ None).distinct.map(relayResolver.resolve)

  private def relayForContact(contact: Contact): ShareRelay = relayResolver.resolve(contact.relayBaseUrl)

  /** Finds a row by id across every known relay — the caller (UI) has no relay context for a
    * bare requestId, only the fan-out list already used to discover it. Returns the relay it was
    * found on too, so the caller can act on it through the *same* relay rather than re-resolving
    * (which could point elsewhere if a contact's relayBaseUrl changed since the row was created).
    */
  private def findShareRequest(requestId: UUID): (ShareRelay, ShareRequest) =
    allRelays().iterator
      .map(relay => Try((relay, relay.getShareRequest(requestId))))
      .collectFirst { case scala.util.Success(pair) => pair }
      .getOrElse(throw IllegalStateException(s"Share request $requestId not found on any known relay"))

  // ── Signature helpers ────────────────────────────────────────────────────

  private def verifyOpen(req: ShareRequest): Boolean =
    contactRepository.getByEdKey(req.senderKey).exists { contact =>
      val canon = PayloadCanonical.forOpen(
        req.secretId,
        req.requestType,
        req.recipientKey,
        req.label,
        req.secretCreatedAt,
        req.shareId,
        req.ciphertext
      )
      identity.verify(canon, req.senderSignature, contact.edPublicKey)
    }

  private def verifyRespond(req: ShareRequest): Boolean =
    req.recipientSignature.exists { sig =>
      contactRepository.getByEdKey(req.recipientKey).exists { contact =>
        val approved = req.state == ShareRequestState.Approved
        val signedCiphertext =
          if approved && req.requestType == ShareRequestType.Retrieve then req.ciphertext else None
        val canon = PayloadCanonical.forRespond(req.id, approved, signedCiphertext)
        identity.verify(canon, sig, contact.edPublicKey)
      }
    }

  // ── Sender flows ──────────────────────────────────────────────────────────

  override def deposit(secret: Array[Byte], label: String, contacts: List[Contact], threshold: Int): Unit =
    val shares = SecretSharing.split(secret, contacts.size, threshold)
    val secretId = UUID.randomUUID()
    val createdAt = Instant.now()
    shares.zip(contacts).foreach { (share, contact) =>
      val ct = encryption.encrypt(share, contact.xPublicKey)
      val canon =
        PayloadCanonical.forOpen(secretId, ShareRequestType.PickUp, contact.edPublicKey, label, createdAt, None, Some(ct))
      val senderSignature = identity.sign(canon)
      val req = relayForContact(contact).openShareRequest(
        secretId,
        contact.edPublicKey,
        label,
        createdAt,
        ShareRequestType.PickUp,
        None,
        Some(ct),
        senderSignature
      )
      shareMetadataRepository.save(ShareMetadata(req.id, secretId, contact.id))
    }
    secretRepository.save(Secret(secretId, label, threshold, contacts.size, createdAt, SecretState.Active))

  override def listSecrets(): List[Secret] = secretRepository.getAll()

  override def syncDistributed(): Unit =
    allRelays().foreach { relay =>
      Try(relay.listShareRequests(Role.Sender, Some(ShareRequestType.PickUp)))
        .getOrElse(Nil)
        .foreach(req =>
          // A row for a holder we no longer have a contact record for can't be re-anchored to a
          // contactId — skip rather than drop the holder's identity on the floor.
          contactRepository.getByEdKey(req.recipientKey).foreach { contact =>
            shareMetadataRepository.save(
              ShareMetadata(req.id, req.secretId, contact.id)
            )
          }
        )
    }
    reconcileDiscarding()

  /** For every Discarding `Secret`, checks whether each remaining holder's fanned-out delete
    * request has been approved; approved ones are cleaned up (relay row deleted, local
    * `ShareMetadata` removed). Once a Discarding secret has no `ShareMetadata` rows left, its
    * `Secret` record itself is removed. See item 11's two-state lifecycle.
    */
  private def reconcileDiscarding(): Unit =
    val discarding = secretRepository.getAll().filter(_.state == SecretState.Discarding)
    if discarding.nonEmpty then
      val discardingIds = discarding.map(_.id).toSet
      val deleteRequests: List[(ShareRelay, ShareRequest)] = allRelays().flatMap { relay =>
        Try(relay.listShareRequests(Role.Sender, Some(ShareRequestType.Delete)))
          .getOrElse(Nil)
          .filter(r => discardingIds.contains(r.secretId))
          .map(relay -> _)
      }
      discarding.foreach { secret =>
        val metasForSecret = shareMetadataRepository.getAll().filter(_.secretId == secret.id)
        metasForSecret.foreach { meta =>
          deleteRequests
            .find { case (_, r) => r.shareId.contains(meta.id) && r.state == ShareRequestState.Approved }
            .foreach { case (relay, _) =>
              Try(relay.deleteShareRequest(meta.id))
              Try(shareMetadataRepository.delete(meta.id))
            }
        }
        val remaining = shareMetadataRepository.getAll().filter(_.secretId == secret.id)
        if remaining.isEmpty then Try(secretRepository.delete(secret.id))
      }

  override def listDistributed(): List[ShareMetadata] = shareMetadataRepository.getAll()

  override def listSentRequests(): List[ShareRequest] =
    allRelays()
      .flatMap(relay => Try(relay.listShareRequests(Role.Sender)).getOrElse(Nil))
      .filterNot(_.requestType == ShareRequestType.PickUp)

  override def requestAll(secretId: UUID): Unit =
    secretRepository.getAll().find(_.id == secretId).foreach { secret =>
      val deposited = shareMetadataRepository.getAll().filter(_.secretId == secretId)
      val existing = allRelays().flatMap(relay =>
        Try(relay.listShareRequests(Role.Sender, Some(ShareRequestType.Retrieve))).getOrElse(Nil)
      )
      deposited.foreach { meta =>
        contactRepository.getById(meta.contactId).foreach { contact =>
          val hasActive = existing.exists(r =>
            r.shareId.contains(meta.id) &&
              (r.state == ShareRequestState.Pending || r.state == ShareRequestState.Approved)
          )
          if !hasActive then
            Try {
              val canon = PayloadCanonical.forOpen(
                meta.secretId,
                ShareRequestType.Retrieve,
                contact.edPublicKey,
                secret.label,
                secret.secretCreatedAt,
                Some(meta.id),
                None
              )
              val senderSignature = identity.sign(canon)
              relayForContact(contact).openShareRequest(
                meta.secretId,
                contact.edPublicKey,
                secret.label,
                secret.secretCreatedAt,
                ShareRequestType.Retrieve,
                Some(meta.id),
                None,
                senderSignature
              )
            }
        }
      }
    }

  override def openRequest(shareId: UUID, requestType: ShareRequestType): ShareRequest =
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
      requestType,
      contact.edPublicKey,
      secret.label,
      secret.secretCreatedAt,
      Some(shareId),
      None
    )
    val senderSignature = identity.sign(canon)
    relayForContact(contact).openShareRequest(
      meta.secretId,
      contact.edPublicKey,
      secret.label,
      secret.secretCreatedAt,
      requestType,
      Some(shareId),
      None,
      senderSignature
    )

  /** Pure read (item 11): collects and decrypts k approved retrieve shares, but never tears down
    * local `ShareMetadata` or relay rows. Use `discardSecret` for teardown — reconstruct is now a
    * *step* toward a possible re-split, not an implicit "I'm done with this" signal.
    */
  override def reconstruct(secretId: UUID): Array[Byte] =
    val secret = secretRepository
      .getAll()
      .find(_.id == secretId)
      .getOrElse(throw IllegalStateException(s"No local record for secret $secretId"))
    val allRequests: List[(ShareRelay, ShareRequest)] =
      allRelays().flatMap(relay =>
        Try(relay.listShareRequests(Role.Sender, Some(ShareRequestType.Retrieve))).getOrElse(Nil).map(relay -> _)
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
    val decrypted = approved.map { case (_, req) =>
      val contact = contacts
        .find(_.edPublicKey.sameElements(req.recipientKey))
        .getOrElse(throw IllegalStateException(s"Contact not found for recipient key"))
      encryption.decrypt(req.ciphertext.get, contact.xPublicKey)
    }
    SecretSharing.combine(decrypted)

  /** Fans out a sender-initiated delete to every known holder of secretId and flips the Secret to
    * Discarding immediately, before any holder has responded — see item 11.
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
      .foreach(share => Try(openRequest(share.id, ShareRequestType.Delete)))

  /** Local-only teardown for a Discarding secret whose holders won't all respond (e.g. a
    * permanently dark holder) — removes the Secret and its remaining `ShareMetadata` rows without
    * waiting for relay confirmation. See item 11.
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
        Try(relay.listShareRequests(Role.Recipient, Some(ShareRequestType.PickUp), Some(ShareRequestState.Pending)))
          .getOrElse(Nil)
      // Unknown sender or unverified senderSignature: skip silently, do not auto-approve.
      pending.filter(verifyOpen).foreach { req =>
        contactRepository.getByEdKey(req.senderKey).foreach { senderContact =>
          if shareRepository.getPlaintextShare(req.id).isEmpty then
            Try {
              val canon = PayloadCanonical.forRespond(req.id, approved = true, ciphertext = None)
              val recipientSignature = identity.sign(canon)
              val responded = relay.respondToShareRequest(req.id, approved = true, recipientSignature = recipientSignature)
              responded.ciphertext.foreach { ct =>
                val plaintext = encryption.decrypt(ct, senderContact.xPublicKey)
                shareRepository.save(
                  HeldShare(
                    id = req.id,
                    secretId = req.secretId,
                    label = req.label,
                    contactId = senderContact.id,
                    createdAt = req.secretCreatedAt,
                    pickedUpAt = Instant.now(),
                    plaintextShare = plaintext
                  )
                )
              }
            }
        }
      }
    }

  override def listHeld(): List[HeldShare] = shareRepository.getAll()

  override def listPendingRequests(): List[ShareRequest] =
    allRelays()
      .flatMap(relay => Try(relay.listShareRequests(Role.Recipient, state = Some(ShareRequestState.Pending))).getOrElse(Nil))
      .filterNot(_.requestType == ShareRequestType.PickUp)
      // A forged delete/retrieve request has no AEAD backstop — must never reach the UI.
      .filter(verifyOpen)

  override def respond(requestId: UUID, approved: Boolean): Unit =
    val (relay, request) = findShareRequest(requestId)
    if !verifyOpen(request) then
      throw SignatureVerificationException(s"senderSignature does not verify for request $requestId")
    val ciphertext =
      if approved && request.requestType == ShareRequestType.Retrieve then
        val pickUpId = request.shareId.getOrElse(
          throw IllegalStateException(s"Retrieve request $requestId has no shareId")
        )
        val plaintext = shareRepository
          .getPlaintextShare(pickUpId)
          .getOrElse(throw IllegalStateException(s"Share $pickUpId not in local storage"))
        // Re-encrypt to the requester's *current* X25519 key — looked up live, not pinned at
        // deposit time. This is what lets reconstruction survive a sender key rotation/recovery
        // (item 7's core reason for existing).
        val requesterContact = contactRepository
          .getByEdKey(request.senderKey)
          .getOrElse(throw IllegalStateException(s"Contact not found for requester"))
        Some(encryption.encrypt(plaintext, requesterContact.xPublicKey))
      else None
    val canon = PayloadCanonical.forRespond(requestId, approved, ciphertext)
    val recipientSignature = identity.sign(canon)
    relay.respondToShareRequest(requestId, approved, ciphertext, recipientSignature)
    if approved && request.requestType == ShareRequestType.Delete then request.shareId.foreach(shareRepository.delete)

  override def deleteHeldShare(shareId: UUID): Unit = shareRepository.delete(shareId)

  override def deleteAllHeldFromSender(contactId: UUID): Unit =
    shareRepository
      .getAll()
      .filter(_.contactId == contactId)
      .foreach(share => shareRepository.delete(share.id))
