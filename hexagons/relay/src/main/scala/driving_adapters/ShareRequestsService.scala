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

  /** `2 <= k <= n <= 255` — the same hard bound `split()`/`combine()` enforce client-side (item 11); re-checked here
    * since Deposit/Inventory rows now carry k/n on the wire.
    */
  private def validKN(k: Option[Int], n: Option[Int]): Boolean = (k, n) match
    case (Some(kk), Some(nn)) => kk >= 2 && kk <= nn && nn <= 255
    case _                    => false

  override def openShareRequest(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: SecretId,
      label: Label,
      secretCreatedAt: Instant,
      transactionType: ShareTransactionType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]],
      k: Option[Int],
      n: Option[Int],
      senderSignature: Signature
  ): Either[Error, ShareRequest] =
    val canon =
      PayloadCanonical.forOpen(
        secretId,
        transactionType,
        recipientKey,
        label,
        secretCreatedAt,
        shareId,
        ciphertext,
        k,
        n
      )
    if !senderKey.verify(canon, senderSignature) then return Left(Error.BadRequest)
    val isRoot = transactionType == ShareTransactionType.Deposit || transactionType == ShareTransactionType.Inventory
    transactionType match
      case ShareTransactionType.Deposit =>
        if ciphertext.isEmpty then return Left(Error.BadRequest)
        if !validKN(k, n) then return Left(Error.BadRequest)
        if repository.hasActiveDeposit(secretId, recipientKey) then return Left(Error.Conflict)
      case ShareTransactionType.Inventory =>
        if ciphertext.isDefined then return Left(Error.BadRequest)
        if !validKN(k, n) then return Left(Error.BadRequest)
      // Fire-and-forget push (see ShareRequest's doc) — no conflict check, self-approved below.
      case _ =>
        if ciphertext.isDefined || k.isDefined || n.isDefined then return Left(Error.BadRequest)
        if repository.hasPendingRequest(secretId, senderKey, recipientKey, transactionType) then
          return Left(Error.Conflict)
    val now = Instant.now()
    val selfApproved = transactionType == ShareTransactionType.Inventory
    val request = ShareRequest(
      id = UUID.randomUUID(),
      secretId = secretId,
      senderKey = senderKey,
      recipientKey = recipientKey,
      label = label,
      secretCreatedAt = secretCreatedAt,
      transactionType = transactionType,
      state = if selfApproved then ShareRequestState.Approved else ShareRequestState.Pending,
      shareId = if isRoot then None else shareId,
      requestedAt = now,
      respondedAt = if selfApproved then Some(now) else None,
      ciphertext = if transactionType == ShareTransactionType.Deposit then ciphertext else None,
      k = if isRoot then k else None,
      n = if isRoot then n else None,
      senderSignature = senderSignature,
      recipientSignature = None
    )
    repository.saveShareRequest(request)
    // Don't return the ciphertext on creation — it's stored in the relay for Bob to
    // collect on approval; Alice already has it and doesn't need it echoed back.
    Right(request.copy(ciphertext = None))

  override def listShareRequests(
      callerKey: PublicKey,
      asSender: Boolean,
      transactionType: Option[ShareTransactionType],
      state: Option[ShareRequestState]
  ): Either[Error, Seq[ShareRequest]] =
    val requests =
      if asSender then repository.getShareRequestsAsSender(callerKey, transactionType, state)
      else repository.getShareRequestsAsRecipient(callerKey, transactionType, state)
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
      ciphertext: Option[Array[Byte]],
      recipientSignature: Signature
  ): Either[Error, ShareRequest] =
    if !recipientKey.verify(PayloadCanonical.forRespond(requestId, approved, ciphertext), recipientSignature) then
      return Left(Error.BadRequest)
    repository.getShareRequestById(requestId) match
      case None                                                  => Left(Error.NotFound)
      case Some(req) if !sameKey(req.recipientKey, recipientKey) => Left(Error.Forbidden)
      case Some(req) if req.state != ShareRequestState.Pending   => Left(Error.Conflict)
      case Some(req)                                             =>
        if approved && req.transactionType == ShareTransactionType.Retrieval && ciphertext.isEmpty then
          return Left(Error.BadRequest)
        val now = Instant.now()
        val newState = if approved then ShareRequestState.Approved else ShareRequestState.Denied
        // For Deposit approval: return stored ciphertext to Bob and clear it from relay.
        // For Retrieval approval: store Bob's ciphertext for Alice to collect later.
        val returnedCt =
          if approved && req.transactionType == ShareTransactionType.Deposit then req.ciphertext else None
        val storedCt = if approved && req.transactionType == ShareTransactionType.Retrieval then ciphertext else None
        repository.updateShareRequest(requestId, newState, now, storedCt, recipientSignature)
        if approved && req.transactionType == ShareTransactionType.Removal then
          repository.deleteShareRequests(req.recipientKey, Some(req.senderKey), Some(req.secretId))
        Right(
          req.copy(
            state = newState,
            respondedAt = Some(now),
            ciphertext = returnedCt.orElse(storedCt),
            recipientSignature = Some(recipientSignature)
          )
        )

  override def deleteShareRequestById(callerKey: PublicKey, requestId: UUID): Either[Error, Unit] =
    repository.getShareRequestById(requestId) match
      case None => Left(Error.NotFound)
      case Some(req) if !sameKey(req.senderKey, callerKey) && !sameKey(req.recipientKey, callerKey) =>
        Left(Error.Forbidden)
      case Some(req) =>
        repository.deleteShareRequestById(requestId)
        if req.transactionType == ShareTransactionType.Deposit then
          repository.deleteShareRequests(req.recipientKey, Some(req.senderKey), Some(req.secretId))
        Right(())

  override def deleteShareRequests(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit] =
    repository.deleteShareRequests(recipientKey, senderKey, secretId)
    Right(())

  override def withdrawShareRequests(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit] =
    repository.withdrawDeposits(recipientKey, senderKey, secretId)
    Right(())
