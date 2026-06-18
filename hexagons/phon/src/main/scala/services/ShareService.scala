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

package services

import driven_ports.ContactRepository
import driven_ports.ShareMetadataRepository
import driven_ports.ShareRelay
import driven_ports.ShareRepository
import driving_ports.ShareManagement
import jakarta.inject.Inject
import shamir.SecretSharing
import value_objects.svo.Contact
import value_objects.svo.HeldShare
import value_objects.svo.Role
import value_objects.svo.ShareMetadata
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareRequestType

import java.time.Instant
import java.util.UUID
import scala.util.Try

class ShareService @Inject() (
    relay: ShareRelay,
    encryption: ShareEncryption,
    shareRepository: ShareRepository,
    shareMetadataRepository: ShareMetadataRepository,
    contactRepository: ContactRepository
) extends ShareManagement:

  // ── Sender flows ──────────────────────────────────────────────────────────

  override def deposit(secret: Array[Byte], label: String, contacts: List[Contact], threshold: Int): Unit =
    val shares = SecretSharing.split(secret, contacts.size, threshold)
    val secretId = UUID.randomUUID()
    val createdAt = Instant.now()
    shares.zip(contacts).foreach { (share, contact) =>
      val ct = encryption.encrypt(share, contact.xPublicKey)
      val req = relay.openShareRequest(secretId, contact.edPublicKey, label, createdAt, ShareRequestType.PickUp, None, Some(ct))
      shareMetadataRepository.save(ShareMetadata(req.id, secretId, label, contact.edPublicKey, createdAt))
    }

  override def syncDistributed(): Unit =
    relay
      .listShareRequests(Role.Sender, Some(ShareRequestType.PickUp))
      .foreach(req => shareMetadataRepository.save(ShareMetadata(req.id, req.secretId, req.label, req.recipientKey, req.secretCreatedAt)))

  override def listDistributed(): List[ShareMetadata] = shareMetadataRepository.getAll()

  override def listSentRequests(): List[ShareRequest] =
    relay.listShareRequests(Role.Sender).filterNot(_.requestType == ShareRequestType.PickUp)

  override def requestAll(secretId: UUID): Unit =
    val deposited = shareMetadataRepository.getAll().filter(_.secretId == secretId)
    val existing  = relay.listShareRequests(Role.Sender, Some(ShareRequestType.Retrieve))
    deposited.foreach { meta =>
      val hasActive = existing.exists(r =>
        r.shareId.contains(meta.id) &&
          (r.state == ShareRequestState.Pending || r.state == ShareRequestState.Approved)
      )
      if !hasActive then
        Try(relay.openShareRequest(meta.secretId, meta.recipientKey, meta.label, meta.secretCreatedAt, ShareRequestType.Retrieve, Some(meta.id), None))
    }

  override def openRequest(shareId: UUID, requestType: ShareRequestType): ShareRequest =
    val meta = shareMetadataRepository.getAll().find(_.id == shareId)
      .getOrElse(throw IllegalArgumentException(s"No local share record for id $shareId"))
    relay.openShareRequest(meta.secretId, meta.recipientKey, meta.label, meta.secretCreatedAt, requestType, Some(shareId), None)

  override def reconstruct(secretId: UUID): Array[Byte] =
    val allRequests = relay.listShareRequests(Role.Sender, Some(ShareRequestType.Retrieve))
    val approved = allRequests.filter(r =>
      r.secretId == secretId &&
        r.state == ShareRequestState.Approved &&
        r.ciphertext.isDefined
    )
    require(approved.size >= 2, s"Need at least 2 approved shares (have ${approved.size})")
    val contacts = contactRepository.getAll()
    val decrypted = approved.map { req =>
      val contact = contacts
        .find(_.edPublicKey.sameElements(req.recipientKey))
        .getOrElse(throw IllegalStateException(s"Contact not found for recipient key"))
      encryption.decrypt(req.ciphertext.get, contact.xPublicKey)
    }
    val secretBytes = SecretSharing.combine(decrypted)
    // Delete the PickUp rows — the relay cascades to Retrieve/Delete rows
    approved.foreach { req =>
      req.shareId.foreach { pickUpId =>
        Try(relay.deleteShareRequest(pickUpId))
        Try(shareMetadataRepository.delete(pickUpId))
      }
    }
    secretBytes

  // ── Recipient flows ───────────────────────────────────────────────────────

  override def syncInbox(): Unit =
    val pending = relay.listShareRequests(Role.Recipient, Some(ShareRequestType.PickUp), Some(ShareRequestState.Pending))
    pending.foreach { req =>
      if shareRepository.getCiphertext(req.id).isEmpty then
        Try {
          val responded = relay.respondToShareRequest(req.id, approved = true)
          responded.ciphertext.foreach { ct =>
            shareRepository.save(
              HeldShare(
                id         = req.id,
                secretId   = req.secretId,
                label      = req.label,
                senderKey  = req.senderKey,
                createdAt  = req.secretCreatedAt,
                pickedUpAt = Instant.now(),
                ciphertext = ct
              )
            )
          }
        }
    }

  override def listHeld(): List[HeldShare] = shareRepository.getAll()

  override def listPendingRequests(): List[ShareRequest] =
    relay
      .listShareRequests(Role.Recipient, state = Some(ShareRequestState.Pending))
      .filterNot(_.requestType == ShareRequestType.PickUp)

  override def respond(requestId: UUID, approved: Boolean): Unit =
    val request = relay.getShareRequest(requestId)
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
    relay.respondToShareRequest(requestId, approved, ciphertext)
    if approved && request.requestType == ShareRequestType.Delete then
      request.shareId.foreach(shareRepository.delete)

  override def deleteHeldShare(shareId: UUID): Unit = shareRepository.delete(shareId)

  override def deleteAllHeldFromSender(senderKey: Array[Byte]): Unit =
    shareRepository
      .getAll()
      .filter(_.senderKey.sameElements(senderKey))
      .foreach(share => shareRepository.delete(share.id))
