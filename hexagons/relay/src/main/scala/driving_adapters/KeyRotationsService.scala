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

import driven_ports.persistence.KeyRotationRepository
import driving_ports.KeyRotations
import jakarta.inject.Inject
import value_objects.*

import java.time.Instant
import java.util.UUID

class KeyRotationsService @Inject() (repository: KeyRotationRepository) extends KeyRotations:

  private def sameKey(a: PublicKey, b: PublicKey): Boolean = a.toBase64Url == b.toBase64Url

  override def pushRotation(
      oldVerifyKey: PublicKey,
      recipientKey: PublicKey,
      newVerifyKey: PublicKey,
      newEncKey: X25519Key,
      newCipherSuite: CipherSuite,
      signature: Signature
  ): Either[Error, KeyRotation] =
    if newVerifyKey.toBytes.length != newCipherSuite.verifyKeyLength then return Left(Error.BadRequest)
    if newEncKey.toBytes.length != newCipherSuite.encKeyLength then return Left(Error.BadRequest)
    val canon = PayloadCanonical.forRotation(recipientKey, newVerifyKey, newEncKey, newCipherSuite)
    if !oldVerifyKey.verify(canon, signature) then return Left(Error.BadRequest)
    val rotation = KeyRotation(
      id = UUID.randomUUID(),
      oldVerifyKey = oldVerifyKey,
      recipientKey = recipientKey,
      newVerifyKey = newVerifyKey,
      newEncKey = newEncKey,
      newCipherSuite = newCipherSuite,
      signature = signature,
      createdAt = Instant.now()
    )
    repository.saveRotation(rotation)
    Right(rotation)

  override def listRotations(recipientKey: PublicKey): Either[Error, Seq[KeyRotation]] =
    Right(repository.getRotationsForRecipient(recipientKey))

  override def deleteRotation(recipientKey: PublicKey, id: UUID): Either[Error, Unit] =
    repository.getRotationById(id) match
      case None                                              => Left(Error.NotFound)
      case Some(r) if !sameKey(r.recipientKey, recipientKey) => Left(Error.Forbidden)
      case Some(_)                                           =>
        repository.deleteRotationById(id)
        Right(())
