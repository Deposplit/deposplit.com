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

import driven_ports.ForgettableIdentityStore
import value_objects.svo.TransportSuite
import value_objects.svo.UnsupportedTransportSuiteException

/** In-memory ForgettableIdentityStore — no file I/O needed for these tests. */
class InMemoryForgettableIdentityStore extends ForgettableIdentityStore:
  private var registered = false
  private var _pseudonym = ""
  private var _edPk, _edSk, _xPk, _xSk: Array[Byte] = Array.empty

  override def isRegistered(): Boolean = registered

  override def save(pseudonym: String, edPk: Array[Byte], edSk: Array[Byte], xPk: Array[Byte], xSk: Array[Byte]): Unit =
    _pseudonym = pseudonym
    _edPk = edPk
    _edSk = edSk
    _xPk = xPk
    _xSk = xSk
    registered = true

  override def pseudonym(): String = _pseudonym
  override def verifyKey(): Array[Byte] = _edPk
  override def signKey(): Array[Byte] = _edSk
  override def encKey(): Array[Byte] = _xPk
  override def decKey(): Array[Byte] = _xSk
  override def forget(): Unit = registered = false

/** Mirrors hexagons/relay's PublicKeyTests valid/tampered/wrong-key trio, for `IdentityService.verify` — the phon-side
  * counterpart of the server's `PublicKey.verify`, used to independently re-verify
  * `senderSignature`/`recipientSignature` (see `PayloadCanonical`).
  */
class IdentityServiceVerifyTests extends munit.FunSuite:

  private def newIdentity(): IdentityService =
    val svc = IdentityService(InMemoryForgettableIdentityStore())
    svc.register("test")
    svc

  test("verify returns true for a valid signature") {
    val alice = newIdentity()
    val message = "hello deposplit".getBytes("UTF-8")
    val sig = alice.sign(message)
    assert(alice.verify(message, sig, alice.verifyKey()))
  }

  test("verify returns false for a tampered message") {
    val alice = newIdentity()
    val message = "hello deposplit".getBytes("UTF-8")
    val sig = alice.sign(message)
    val tampered = "hello depospliz".getBytes("UTF-8")
    assert(!alice.verify(tampered, sig, alice.verifyKey()))
  }

  test("verify returns false when checked against a different key") {
    val alice = newIdentity()
    val bob = newIdentity()
    val message = "hello deposplit".getBytes("UTF-8")
    val sig = alice.sign(message)
    assert(!bob.verify(message, sig, bob.verifyKey()))
  }

  // ---------------------------------------------------------------------------
  // generateNewKeyPair() / activateKeyPair() — item 9's identity-regen trigger
  // ---------------------------------------------------------------------------

  test("generateNewKeyPair does not touch storage") {
    val alice = newIdentity()
    val originalVerifyKey = alice.verifyKey()
    val originalEncKey = alice.encKey()
    val candidate = alice.generateNewKeyPair()
    assert(!candidate.verifyKey.sameElements(originalVerifyKey))
    assert(!candidate.encKey.sameElements(originalEncKey))
    // Unpersisted — the live identity hasn't moved.
    assert(alice.verifyKey().sameElements(originalVerifyKey))
    assert(alice.encKey().sameElements(originalEncKey))
  }

  test("activateKeyPair persists the new keys and preserves the pseudonym") {
    val alice = newIdentity()
    val candidate = alice.generateNewKeyPair()
    alice.activateKeyPair(candidate)
    assert(alice.verifyKey().sameElements(candidate.verifyKey))
    assert(alice.encKey().sameElements(candidate.encKey))
    assertEquals(alice.pseudonym(), "test")
  }

  test("sign after activateKeyPair verifies against the new key not the old") {
    val alice = newIdentity()
    val oldVerifyKey = alice.verifyKey()
    val candidate = alice.generateNewKeyPair()
    alice.activateKeyPair(candidate)
    val message = "post-rotation message".getBytes("UTF-8")
    val sig = alice.sign(message)
    assert(alice.verify(message, sig, candidate.verifyKey))
    assert(!alice.verify(message, sig, oldVerifyKey))
  }

  // ---------------------------------------------------------------------------
  // encrypt()/decrypt() suite-tag wire format — item 14
  // ---------------------------------------------------------------------------

  test("encrypt prepends the current TransportSuite tag and decrypt round-trips") {
    val alice = newIdentity()
    val bob = newIdentity()
    val plaintext = "a share of a secret".getBytes("UTF-8")

    // Alice (sender) encrypts to Bob's public enc key; Bob (recipient) decrypts with his own
    // private key + Alice's public enc key — the same static-static DH shape ShareService uses.
    val ciphertext = alice.encrypt(plaintext, bob.encKey())

    assertEquals(ciphertext.head, TransportSuite.current.tag)
    val decrypted = bob.decrypt(ciphertext, alice.encKey())
    assert(decrypted.sameElements(plaintext))
  }

  test("decrypt throws UnsupportedTransportSuiteException for an unrecognized suite tag") {
    val alice = newIdentity()
    val bob = newIdentity()
    val ciphertext = alice.encrypt("hi".getBytes("UTF-8"), bob.encKey())
    val tampered = Array(0x7f.toByte) ++ ciphertext.drop(1)

    intercept[UnsupportedTransportSuiteException] {
      bob.decrypt(tampered, alice.encKey())
    }
  }

  test("decrypt throws UnsupportedTransportSuiteException for empty ciphertext") {
    val alice = newIdentity()
    val bob = newIdentity()

    intercept[UnsupportedTransportSuiteException] {
      bob.decrypt(Array.emptyByteArray, alice.encKey())
    }
  }
