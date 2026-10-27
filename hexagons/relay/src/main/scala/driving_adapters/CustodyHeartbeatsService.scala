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

import driven_ports.persistence.CustodyHeartbeatRepository
import driving_ports.CustodyHeartbeats
import jakarta.inject.Inject
import value_objects.*

import java.time.Instant
import java.util.UUID

class CustodyHeartbeatsService @Inject() (repository: CustodyHeartbeatRepository) extends CustodyHeartbeats:

  private def sameKey(a: PublicKey, b: PublicKey): Boolean =
    a.toBase64Url == b.toBase64Url

  override def pushHeartbeat(
      holderKey: PublicKey,
      ownerKey: PublicKey,
      secretIds: Seq[UUID],
      optedOut: Boolean,
      emittedAt: Instant,
      signature: Signature
  ): Either[Error, CustodyHeartbeat] =
    val canon = PayloadCanonical.forHeartbeat(ownerKey, secretIds, optedOut, emittedAt)
    if !holderKey.verify(canon, signature) then return Left(Error.BadRequest)
    val now = Instant.now()
    if emittedAt.isAfter(now) then return Left(Error.BadRequest)
    val heartbeat = CustodyHeartbeat(
      id = UUID.randomUUID(),
      holderKey = holderKey,
      ownerKey = ownerKey,
      secretIds = secretIds,
      optedOut = optedOut,
      emittedAt = emittedAt,
      signature = signature,
      createdAt = now
    )
    Right(repository.upsertHeartbeat(heartbeat))

  override def listHeartbeats(ownerKey: PublicKey): Either[Error, Seq[CustodyHeartbeat]] =
    Right(repository.getHeartbeatsForOwner(ownerKey))

  override def deleteHeartbeat(ownerKey: PublicKey, id: UUID): Either[Error, Unit] =
    repository.getHeartbeatById(id) match
      case None                                      => Left(Error.NotFound)
      case Some(h) if !sameKey(h.ownerKey, ownerKey) => Left(Error.Forbidden)
      case Some(_)                                   =>
        repository.deleteHeartbeatById(id)
        Right(())

  override def expireHeartbeats(receivedBefore: Instant): Int =
    repository.deleteHeartbeatsReceivedBefore(receivedBefore)
