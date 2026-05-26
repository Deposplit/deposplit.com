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

import driven_ports.{ContactRepository, ShareRelay, ShareRepository}
import driving_ports.ShareManagement
import shamir.SecretSharing
import value_objects.{Contact, HeldShare, Role, ShareMetadata, ShareRequest, ShareRequestState, ShareRequestType}
import java.util.UUID
import scala.util.Try

class ShareService(
  relay: ShareRelay,
  encryption: ShareEncryption,
  shareRepository: ShareRepository,
  contactRepository: ContactRepository,
) extends ShareManagement:

  override def deposit(secret: Array[Byte], label: String, contacts: List[Contact], threshold: Int): Unit =
    val shares   = SecretSharing.split(secret, contacts.size, threshold)
    val secretId = UUID.randomUUID()
    shares.zip(contacts).foreach { (share, contact) =>
      val ciphertext = encryption.encrypt(share, contact.xPublicKey)
      relay.depositShare(secretId, label, contact.edPublicKey, ciphertext)
    }

  override def listDistributed(): List[ShareMetadata] = relay.listShares(Role.Sender)

  override def listSentRequests(): List[ShareRequest] = relay.listShareRequests(Role.Sender)

  override def requestAll(secretId: UUID): Unit =
    val distributed = relay.listShares(Role.Sender).filter(_.secretId == secretId)
    val existing    = relay.listShareRequests(Role.Sender)
    distributed.foreach { share =>
      val hasActive = existing.exists { r =>
        r.share.id == share.id &&
        r.requestType == ShareRequestType.Retrieve &&
        (r.state == ShareRequestState.Pending || r.state == ShareRequestState.Approved)
      }
      if !hasActive then Try(relay.openShareRequest(share.id, ShareRequestType.Retrieve))
    }

  override def openRequest(shareId: UUID, requestType: ShareRequestType): ShareRequest =
    relay.openShareRequest(shareId, requestType)

  override def reconstruct(secretId: UUID): Array[Byte] =
    val allRequests = relay.listShareRequests(Role.Sender)
    val approved    = allRequests.filter { r =>
      r.share.secretId == secretId &&
      r.requestType == ShareRequestType.Retrieve &&
      r.state == ShareRequestState.Approved &&
      r.ciphertext.isDefined
    }
    require(approved.size >= 2, s"Need at least 2 approved shares (have ${approved.size})")
    val contacts  = contactRepository.getAll()
    val decrypted = approved.map { req =>
      val contact = contacts
        .find(_.edPublicKey.sameElements(req.share.recipientKey))
        .getOrElse(throw IllegalStateException("Contact not found for recipient key"))
      encryption.decrypt(req.ciphertext.get, contact.xPublicKey)
    }
    val secretBytes = SecretSharing.combine(decrypted)
    approved.foreach { req => Try(relay.deleteShare(req.share.id)) }
    secretBytes

  override def syncInbox(): Unit =
    val inbox = relay.listShares(Role.Recipient)
    inbox.foreach { meta =>
      if shareRepository.getCiphertext(meta.id).isEmpty then
        Try {
          val ciphertext = relay.pickUpShare(meta.id)
          shareRepository.save(HeldShare(
            id         = meta.id,
            secretId   = meta.secretId,
            label      = meta.label,
            senderKey  = meta.senderKey,
            createdAt  = meta.createdAt,
            ciphertext = ciphertext,
          ))
        }
    }

  override def listHeld(): List[HeldShare] = shareRepository.getAll()

  override def listPendingRequests(): List[ShareRequest] =
    relay.listShareRequests(Role.Recipient, Some(ShareRequestState.Pending))

  override def respond(requestId: UUID, approved: Boolean): Unit =
    val request    = relay.getShareRequest(requestId)
    val ciphertext =
      if approved && request.requestType == ShareRequestType.Retrieve then
        Some(
          shareRepository.getCiphertext(request.share.id)
            .getOrElse(throw IllegalStateException("Share ciphertext not found in local storage"))
        )
      else None
    relay.respondToShareRequest(requestId, approved, ciphertext)
    if approved && request.requestType == ShareRequestType.Delete then
      shareRepository.delete(request.share.id)

  override def deleteHeldShare(shareId: UUID): Unit = shareRepository.delete(shareId)

  override def deleteAllHeldFromSender(senderKey: Array[Byte]): Unit =
    shareRepository.getAll()
      .filter(_.senderKey.sameElements(senderKey))
      .foreach(share => shareRepository.delete(share.id))
