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

  override def depositShare(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: SecretId,
      label: Label,
      createdAt: Instant,
      ciphertext: Array[Byte]
  ): Either[Error, Unit] =
    repository.getShare(secretId, senderKey, recipientKey) match
      case Some(_) => Left(Error.Conflict)
      case None    =>
        repository.saveShare(Share(secretId, senderKey, recipientKey, label, createdAt, ciphertext))
        Right(())

  override def listShares(
      senderKey: PublicKey,
      recipientKey: PublicKey
  ): Either[Error, Seq[ShareMetadata]] =
    Right(repository.getShareMetadata(senderKey, recipientKey))

  override def requestRetrieval(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: SecretId
  ): Either[Error, UUID] =
    repository.getShare(secretId, senderKey, recipientKey) match
      case None    => Left(Error.NotFound)
      case Some(_) =>
        val request = ShareRequest(
          id = UUID.randomUUID(),
          requestType = ShareRequestType.Retrieve,
          secretId = Some(secretId),
          senderKey = senderKey,
          recipientKey = recipientKey,
          state = ShareRequestState.Pending,
          createdAt = Instant.now()
        )
        repository.saveShareRequest(request)
        Right(request.id)

  override def requestDeletion(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: Option[SecretId]
  ): Either[Error, UUID] =
    val exists = secretId match
      case None      => true
      case Some(sid) => repository.getShare(sid, senderKey, recipientKey).isDefined
    if !exists then Left(Error.NotFound)
    else
      val request = ShareRequest(
        id = UUID.randomUUID(),
        requestType = ShareRequestType.Delete,
        secretId = secretId,
        senderKey = senderKey,
        recipientKey = recipientKey,
        state = ShareRequestState.Pending,
        createdAt = Instant.now()
      )
      repository.saveShareRequest(request)
      Right(request.id)

  override def listPendingRequests(recipientKey: PublicKey): Either[Error, Seq[ShareRequest]] =
    Right(repository.getPendingShareRequests(recipientKey))

  override def approveRetrieval(
      recipientKey: PublicKey,
      requestId: UUID
  ): Either[Error, Array[Byte]] =
    repository.getShareRequest(requestId, recipientKey) match
      case None                                                      => Left(Error.NotFound)
      case Some(req) if req.requestType != ShareRequestType.Retrieve => Left(Error.NotFound)
      case Some(req) if req.state != ShareRequestState.Pending       => Left(Error.NotFound)
      case Some(req)                                                 =>
        req.secretId match
          case None      => Left(Error.NotFound) // invariant: Retrieve always has a secretId
          case Some(sid) =>
            repository.getShare(sid, req.senderKey, req.recipientKey) match
              case None        => Left(Error.NotFound)
              case Some(share) =>
                repository.updateShareRequestState(requestId, ShareRequestState.Approved)
                Right(share.ciphertext)

  override def approveDeletion(
      recipientKey: PublicKey,
      requestId: UUID
  ): Either[Error, Unit] =
    repository.getShareRequest(requestId, recipientKey) match
      case None                                                    => Left(Error.NotFound)
      case Some(req) if req.requestType != ShareRequestType.Delete => Left(Error.NotFound)
      case Some(req) if req.state != ShareRequestState.Pending     => Left(Error.NotFound)
      case Some(req)                                               =>
        repository.updateShareRequestState(requestId, ShareRequestState.Approved)
        repository.deleteShares(req.recipientKey, Some(req.senderKey), req.secretId)
        Right(())

  override def denyRequest(
      recipientKey: PublicKey,
      requestId: UUID
  ): Either[Error, Unit] =
    repository.getShareRequest(requestId, recipientKey) match
      case None                                                => Left(Error.NotFound)
      case Some(req) if req.state != ShareRequestState.Pending => Left(Error.NotFound)
      case Some(_)                                             =>
        repository.updateShareRequestState(requestId, ShareRequestState.Denied)
        Right(())

  override def deleteShares(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit] =
    repository.deleteShares(recipientKey, senderKey, secretId)
    Right(())
