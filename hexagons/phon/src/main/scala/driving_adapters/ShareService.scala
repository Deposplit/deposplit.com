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

  private def relayForKey(edPublicKey: Array[Byte]): ShareRelay =
    relayResolver.resolve(contactRepository.getByEdKey(edPublicKey).flatMap(_.relayBaseUrl))

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
      shareMetadataRepository.save(ShareMetadata(req.id, secretId, label, contact.edPublicKey, createdAt))
    }

  override def syncDistributed(): Unit =
    allRelays().foreach { relay =>
      Try(relay.listShareRequests(Role.Sender, Some(ShareRequestType.PickUp)))
        .getOrElse(Nil)
        .foreach(req =>
          shareMetadataRepository.save(
            ShareMetadata(req.id, req.secretId, req.label, req.recipientKey, req.secretCreatedAt)
          )
        )
    }

  override def listDistributed(): List[ShareMetadata] = shareMetadataRepository.getAll()

  override def listSentRequests(): List[ShareRequest] =
    allRelays()
      .flatMap(relay => Try(relay.listShareRequests(Role.Sender)).getOrElse(Nil))
      .filterNot(_.requestType == ShareRequestType.PickUp)

  override def requestAll(secretId: UUID): Unit =
    val deposited = shareMetadataRepository.getAll().filter(_.secretId == secretId)
    val existing = allRelays().flatMap(relay =>
      Try(relay.listShareRequests(Role.Sender, Some(ShareRequestType.Retrieve))).getOrElse(Nil)
    )
    deposited.foreach { meta =>
      val hasActive = existing.exists(r =>
        r.shareId.contains(meta.id) &&
          (r.state == ShareRequestState.Pending || r.state == ShareRequestState.Approved)
      )
      if !hasActive then
        Try {
          val canon = PayloadCanonical.forOpen(
            meta.secretId,
            ShareRequestType.Retrieve,
            meta.recipientKey,
            meta.label,
            meta.secretCreatedAt,
            Some(meta.id),
            None
          )
          val senderSignature = identity.sign(canon)
          relayForKey(meta.recipientKey).openShareRequest(
            meta.secretId,
            meta.recipientKey,
            meta.label,
            meta.secretCreatedAt,
            ShareRequestType.Retrieve,
            Some(meta.id),
            None,
            senderSignature
          )
        }
    }

  override def openRequest(shareId: UUID, requestType: ShareRequestType): ShareRequest =
    val meta = shareMetadataRepository
      .getAll()
      .find(_.id == shareId)
      .getOrElse(throw IllegalArgumentException(s"No local share record for id $shareId"))
    val canon = PayloadCanonical.forOpen(
      meta.secretId,
      requestType,
      meta.recipientKey,
      meta.label,
      meta.secretCreatedAt,
      Some(shareId),
      None
    )
    val senderSignature = identity.sign(canon)
    relayForKey(meta.recipientKey).openShareRequest(
      meta.secretId,
      meta.recipientKey,
      meta.label,
      meta.secretCreatedAt,
      requestType,
      Some(shareId),
      None,
      senderSignature
    )

  override def reconstruct(secretId: UUID): Array[Byte] =
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
    require(approved.size >= 2, s"Need at least 2 approved shares (have ${approved.size})")
    val contacts = contactRepository.getAll()
    val decrypted = approved.map { case (_, req) =>
      val contact = contacts
        .find(_.edPublicKey.sameElements(req.recipientKey))
        .getOrElse(throw IllegalStateException(s"Contact not found for recipient key"))
      encryption.decrypt(req.ciphertext.get, contact.xPublicKey)
    }
    val secretBytes = SecretSharing.combine(decrypted)
    // Delete via the same relay each row was found on — the relay cascades to Retrieve/Delete rows.
    approved.foreach { case (relay, req) =>
      req.shareId.foreach { pickUpId =>
        Try(relay.deleteShareRequest(pickUpId))
        Try(shareMetadataRepository.delete(pickUpId))
      }
    }
    secretBytes

  // ── Recipient flows ───────────────────────────────────────────────────────

  override def syncInbox(): Unit =
    allRelays().foreach { relay =>
      val pending =
        Try(relay.listShareRequests(Role.Recipient, Some(ShareRequestType.PickUp), Some(ShareRequestState.Pending)))
          .getOrElse(Nil)
      // Unknown sender or unverified senderSignature: skip silently, do not auto-approve.
      pending.filter(verifyOpen).foreach { req =>
        if shareRepository.getCiphertext(req.id).isEmpty then
          Try {
            val canon = PayloadCanonical.forRespond(req.id, approved = true, ciphertext = None)
            val recipientSignature = identity.sign(canon)
            val responded = relay.respondToShareRequest(req.id, approved = true, recipientSignature = recipientSignature)
            responded.ciphertext.foreach { ct =>
              shareRepository.save(
                HeldShare(
                  id = req.id,
                  secretId = req.secretId,
                  label = req.label,
                  senderKey = req.senderKey,
                  createdAt = req.secretCreatedAt,
                  pickedUpAt = Instant.now(),
                  ciphertext = ct
                )
              )
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
        Some(
          shareRepository
            .getCiphertext(pickUpId)
            .getOrElse(throw IllegalStateException(s"Share $pickUpId not in local storage"))
        )
      else None
    val canon = PayloadCanonical.forRespond(requestId, approved, ciphertext)
    val recipientSignature = identity.sign(canon)
    relay.respondToShareRequest(requestId, approved, ciphertext, recipientSignature)
    if approved && request.requestType == ShareRequestType.Delete then request.shareId.foreach(shareRepository.delete)

  override def deleteHeldShare(shareId: UUID): Unit = shareRepository.delete(shareId)

  override def deleteAllHeldFromSender(senderKey: Array[Byte]): Unit =
    shareRepository
      .getAll()
      .filter(_.senderKey.sameElements(senderKey))
      .foreach(share => shareRepository.delete(share.id))
