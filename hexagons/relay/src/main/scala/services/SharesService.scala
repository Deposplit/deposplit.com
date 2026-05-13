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

import com.google.inject.Inject
import driven_ports.persistence.ShareRepository
import driving_ports.Shares
import value_objects.*
import java.time.Instant
import java.util.UUID

class SharesService @Inject() (repository: ShareRepository) extends Shares:

  private def sameKey(a: PublicKey, b: PublicKey): Boolean =
    a.toBase64Url == b.toBase64Url

  private def toMetadata(s: Share): ShareMetadata =
    ShareMetadata(s.id, s.secretId, s.senderKey, s.recipientKey, s.label, s.createdAt, s.pickedUpAt)

  override def depositShare(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: SecretId,
      label: Label,
      createdAt: Instant,
      ciphertext: Array[Byte]
  ): Either[Error, ShareMetadata] =
    repository.getShare(secretId, senderKey, recipientKey) match
      case Some(_) => Left(Error.Conflict)
      case None =>
        val share = Share(UUID.randomUUID(), secretId, senderKey, recipientKey, label, createdAt, Some(ciphertext), None)
        repository.saveShare(share)
        Right(toMetadata(share))

  override def listShares(
      callerKey: PublicKey,
      asSender: Boolean,
      counterpartyKey: Option[PublicKey]
  ): Either[Error, Seq[ShareMetadata]] =
    if asSender then Right(repository.getSharesAsSender(callerKey, counterpartyKey))
    else Right(repository.getSharesAsRecipient(callerKey, counterpartyKey))

  override def openShareRequest(
      senderKey: PublicKey,
      shareId: UUID,
      requestType: ShareRequestType
  ): Either[Error, ShareRequest] =
    repository.getShareById(shareId) match
      case None => Left(Error.NotFound)
      case Some(share) if !sameKey(share.senderKey, senderKey) => Left(Error.Forbidden)
      case Some(share) =>
        if repository.hasPendingRequest(shareId, requestType) then Left(Error.Conflict)
        else
          val request = ShareRequest(
            id          = UUID.randomUUID(),
            share       = toMetadata(share),
            requestType = requestType,
            state       = ShareRequestState.Pending,
            createdAt   = Instant.now(),
            respondedAt = None,
            ciphertext  = None
          )
          repository.saveShareRequest(request)
          Right(request)

  override def pickUpShare(
      recipientKey: PublicKey,
      shareId: UUID
  ): Either[Error, Array[Byte]] =
    repository.getShareById(shareId) match
      case None => Left(Error.NotFound)
      case Some(share) if !sameKey(share.recipientKey, recipientKey) => Left(Error.Forbidden)
      case Some(share) if share.pickedUpAt.isDefined => Left(Error.Conflict)
      case Some(share) =>
        val ct = share.ciphertext.getOrElse(sys.error(s"invariant violated: share ${shareId} has no ciphertext but picked_up_at is null"))
        repository.pickUpShare(shareId)
        Right(ct)

  override def listShareRequests(
      callerKey: PublicKey,
      asSender: Boolean,
      state: Option[ShareRequestState]
  ): Either[Error, Seq[ShareRequest]] =
    val requests =
      if asSender then repository.getShareRequestsAsSender(callerKey, state)
      else repository.getShareRequestsAsRecipient(callerKey, state)
    Right(requests)

  override def getShareRequest(
      callerKey: PublicKey,
      requestId: UUID
  ): Either[Error, ShareRequest] =
    repository.getShareRequestById(requestId) match
      case None => Left(Error.NotFound)
      case Some(req)
        if !sameKey(req.share.senderKey, callerKey) && !sameKey(req.share.recipientKey, callerKey) =>
        Left(Error.Forbidden)
      case Some(req) => Right(req)

  override def respondToShareRequest(
      recipientKey: PublicKey,
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]]
  ): Either[Error, ShareRequest] =
    repository.getShareRequestById(requestId) match
      case None => Left(Error.NotFound)
      case Some(req) if !sameKey(req.share.recipientKey, recipientKey) => Left(Error.Forbidden)
      case Some(req) if req.state != ShareRequestState.Pending         => Left(Error.Conflict)
      case Some(req) =>
        if approved && req.requestType == ShareRequestType.Retrieve && ciphertext.isEmpty then
          return Left(Error.BadRequest)
        val now      = Instant.now()
        val newState = if approved then ShareRequestState.Approved else ShareRequestState.Denied
        val storedCt = if approved && req.requestType == ShareRequestType.Retrieve then ciphertext else None
        repository.updateShareRequest(requestId, newState, now, storedCt)
        if approved && req.requestType == ShareRequestType.Delete then
          repository.deleteShares(req.share.recipientKey, Some(req.share.senderKey), Some(req.share.secretId))
        Right(req.copy(state = newState, respondedAt = Some(now), ciphertext = storedCt))

  override def deleteShareById(
      callerKey: PublicKey,
      shareId: UUID
  ): Either[Error, Unit] =
    repository.getShareById(shareId) match
      case None => Left(Error.NotFound)
      case Some(share) if sameKey(share.recipientKey, callerKey) =>
        repository.deleteShareByPK(shareId)
        Right(())
      case Some(share) if sameKey(share.senderKey, callerKey) =>
        if share.pickedUpAt.isDefined then
          repository.deleteShareByPK(shareId)
          Right(())
        else
          Left(Error.Conflict)
      case _ => Left(Error.Forbidden)

  override def deleteShares(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit] =
    repository.deleteShares(recipientKey, senderKey, secretId)
    Right(())
