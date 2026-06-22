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

import driven_ports.persistence.ShareRepository
import driving_ports.ShareRequests
import jakarta.inject.Inject
import value_objects.*

import java.time.Instant
import java.util.UUID

class ShareRequestsService @Inject() (repository: ShareRepository) extends ShareRequests:

  private def sameKey(a: PublicKey, b: PublicKey): Boolean =
    a.toBase64Url == b.toBase64Url

  override def openShareRequest(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: SecretId,
      label: Label,
      secretCreatedAt: Instant,
      requestType: ShareRequestType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]]
  ): Either[Error, ShareRequest] =
    requestType match
      case ShareRequestType.PickUp =>
        if ciphertext.isEmpty then return Left(Error.BadRequest)
        if repository.hasActivePickUp(secretId, recipientKey) then return Left(Error.Conflict)
      case _ =>
        if repository.hasPendingRequest(secretId, senderKey, recipientKey, requestType) then return Left(Error.Conflict)
    val request = ShareRequest(
      id = UUID.randomUUID(),
      secretId = secretId,
      senderKey = senderKey,
      recipientKey = recipientKey,
      label = label,
      secretCreatedAt = secretCreatedAt,
      requestType = requestType,
      state = ShareRequestState.Pending,
      shareId = if requestType == ShareRequestType.PickUp then None else shareId,
      requestedAt = Instant.now(),
      respondedAt = None,
      ciphertext = if requestType == ShareRequestType.PickUp then ciphertext else None
    )
    repository.saveShareRequest(request)
    // Don't return the ciphertext on creation — it's stored in the relay for Bob to
    // collect on approval; Alice already has it and doesn't need it echoed back.
    Right(request.copy(ciphertext = None))

  override def listShareRequests(
      callerKey: PublicKey,
      asSender: Boolean,
      requestType: Option[ShareRequestType],
      state: Option[ShareRequestState]
  ): Either[Error, Seq[ShareRequest]] =
    val requests =
      if asSender then repository.getShareRequestsAsSender(callerKey, requestType, state)
      else repository.getShareRequestsAsRecipient(callerKey, requestType, state)
    Right(requests)

  override def getShareRequest(callerKey: PublicKey, requestId: UUID): Either[Error, ShareRequest] =
    repository.getShareRequestById(requestId) match
      case None => Left(Error.NotFound)
      case Some(req) if !sameKey(req.senderKey, callerKey) && !sameKey(req.recipientKey, callerKey) =>
        Left(Error.Forbidden)
      case Some(req) => Right(req)

  override def respondToShareRequest(
      recipientKey: PublicKey,
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]]
  ): Either[Error, ShareRequest] =
    repository.getShareRequestById(requestId) match
      case None                                                  => Left(Error.NotFound)
      case Some(req) if !sameKey(req.recipientKey, recipientKey) => Left(Error.Forbidden)
      case Some(req) if req.state != ShareRequestState.Pending   => Left(Error.Conflict)
      case Some(req)                                             =>
        if approved && req.requestType == ShareRequestType.Retrieve && ciphertext.isEmpty then
          return Left(Error.BadRequest)
        val now = Instant.now()
        val newState = if approved then ShareRequestState.Approved else ShareRequestState.Denied
        // For PickUp approval: return stored ciphertext to Bob and clear it from relay.
        // For Retrieve approval: store Bob's ciphertext for Alice to collect later.
        val returnedCt = if approved && req.requestType == ShareRequestType.PickUp then req.ciphertext else None
        val storedCt = if approved && req.requestType == ShareRequestType.Retrieve then ciphertext else None
        repository.updateShareRequest(requestId, newState, now, storedCt)
        if approved && req.requestType == ShareRequestType.Delete then
          repository.deleteShareRequests(req.recipientKey, Some(req.senderKey), Some(req.secretId))
        Right(req.copy(state = newState, respondedAt = Some(now), ciphertext = returnedCt.orElse(storedCt)))

  override def deleteShareRequestById(callerKey: PublicKey, requestId: UUID): Either[Error, Unit] =
    repository.getShareRequestById(requestId) match
      case None => Left(Error.NotFound)
      case Some(req) if !sameKey(req.senderKey, callerKey) && !sameKey(req.recipientKey, callerKey) =>
        Left(Error.Forbidden)
      case Some(req) =>
        repository.deleteShareRequestById(requestId)
        if req.requestType == ShareRequestType.PickUp then
          repository.deleteShareRequests(req.recipientKey, Some(req.senderKey), Some(req.secretId))
        Right(())

  override def deleteShareRequests(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit] =
    repository.deleteShareRequests(recipientKey, senderKey, secretId)
    Right(())
