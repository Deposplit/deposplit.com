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
import value_objects.*

import java.util.UUID

// ---------------------------------------------------------------------------
// In-memory test double — same shape as InMemoryShareRepository (ShareRequestsServiceTests.scala).
// ---------------------------------------------------------------------------
class InMemoryKeyRotationRepository extends KeyRotationRepository:

  private var rotations: Seq[KeyRotation] = Seq.empty

  private def sameKey(a: PublicKey, b: PublicKey): Boolean = a.toBase64Url == b.toBase64Url

  override def saveRotation(rotation: KeyRotation): Unit =
    rotations = rotations :+ rotation

  override def getRotationById(id: UUID): Option[KeyRotation] =
    rotations.find(_.id == id)

  override def getRotationsForRecipient(recipientKey: PublicKey): Seq[KeyRotation] =
    rotations.filter(r => sameKey(r.recipientKey, recipientKey))

  override def deleteRotationById(id: UUID): Unit =
    rotations = rotations.filterNot(_.id == id)

// ---------------------------------------------------------------------------
// Tests — reuses ShareRequestsServiceTests's Fixtures (same package) for keypairs.
// ---------------------------------------------------------------------------
class KeyRotationsServiceTests extends munit.FunSuite:

  import Fixtures.*

  private def newService(): (InMemoryKeyRotationRepository, KeyRotationsService) =
    val repo = InMemoryKeyRotationRepository()
    (repo, KeyRotationsService(repo))

  private val newEncKey: X25519Key = X25519Key.fromBytes(Array.fill(32)(0x09.toByte)).getOrElse(fail("bad fixture key"))
  private val cipherSuite = CipherSuite.current

  /** Signs and pushes a rotation notice — the signing counterpart of
    * `service.pushRotation`, mirroring `ShareRequestsServiceTests.open`.
    */
  private def push(
      service: KeyRotationsService,
      oldKey: PublicKey,
      recipient: PublicKey,
      newVerifyKey: PublicKey = charlie,
      newEnc: X25519Key = newEncKey,
      suite: CipherSuite = cipherSuite
  ): Either[Error, KeyRotation] =
    val sig = signerFor(oldKey).sign(PayloadCanonical.forRotation(recipient, newVerifyKey, newEnc, suite))
    service.pushRotation(oldKey, recipient, newVerifyKey, newEnc, suite, sig)

  test("pushRotation with a valid signature succeeds and stores the row") {
    val (repo, service) = newService()
    val result = push(service, alice, bob)
    assert(result.isRight)
    val rotation = result.getOrElse(fail("not right"))
    assertEquals(rotation.oldVerifyKey.toBase64Url, alice.toBase64Url)
    assertEquals(rotation.recipientKey.toBase64Url, bob.toBase64Url)
    assertEquals(rotation.newVerifyKey.toBase64Url, charlie.toBase64Url)
    assertEquals(rotation.newCipherSuite, cipherSuite)
    assert(repo.getRotationById(rotation.id).isDefined)
  }

  test("pushRotation returns BadRequest when the signature doesn't verify against oldVerifyKey") {
    val (_, service) = newService()
    // Signed by bob but claiming to be alice's rotation — signature won't verify against alice.
    val badSig = signerFor(bob).sign(PayloadCanonical.forRotation(bob, charlie, newEncKey, cipherSuite))
    val result = service.pushRotation(alice, bob, charlie, newEncKey, cipherSuite, badSig)
    assertEquals(result, Left(Error.BadRequest))
  }

  test("pushRotation returns BadRequest when newVerifyKey's length doesn't match newCipherSuite") {
    val (_, service) = newService()
    val tooShort = PublicKey.fromBytes(Array.fill(16)(0x07.toByte)).getOrElse(fail("bad fixture key"))
    val sig = signerFor(alice).sign(PayloadCanonical.forRotation(bob, tooShort, newEncKey, cipherSuite))
    val result = service.pushRotation(alice, bob, tooShort, newEncKey, cipherSuite, sig)
    assertEquals(result, Left(Error.BadRequest))
  }

  test("listRotations returns only rows addressed to the caller") {
    val (_, service) = newService()
    push(service, alice, bob)
    push(service, charlie, alice)
    val forBob = service.listRotations(bob).getOrElse(fail("not right"))
    assertEquals(forBob.size, 1)
    assertEquals(forBob.head.oldVerifyKey.toBase64Url, alice.toBase64Url)
  }

  test("deleteRotation as the recipient succeeds and removes the row") {
    val (repo, service) = newService()
    val rotation = push(service, alice, bob).getOrElse(fail("push failed"))
    assertEquals(service.deleteRotation(bob, rotation.id), Right(()))
    assertEquals(repo.getRotationById(rotation.id), None)
  }

  test("deleteRotation returns Forbidden when the caller is not the recipient") {
    val (_, service) = newService()
    val rotation = push(service, alice, bob).getOrElse(fail("push failed"))
    assertEquals(service.deleteRotation(charlie, rotation.id), Left(Error.Forbidden))
  }

  test("deleteRotation returns NotFound for an unknown id") {
    val (_, service) = newService()
    assertEquals(service.deleteRotation(bob, UUID.randomUUID()), Left(Error.NotFound))
  }
