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
  private var _verifyKey, _signKey, _encKey, _decKey: Array[Byte] = Array.empty
  private var _previousDecKey: Option[Array[Byte]] = None

  override def isRegistered(): Boolean = registered

  override def save(
      pseudonym: String,
      verifyKey: Array[Byte],
      signKey: Array[Byte],
      encKey: Array[Byte],
      decKey: Array[Byte]
  ): Unit =
    _pseudonym = pseudonym
    _verifyKey = verifyKey
    _signKey = signKey
    _encKey = encKey
    _decKey = decKey
    _previousDecKey = None
    registered = true

  override def rotate(
      verifyKey: Array[Byte],
      signKey: Array[Byte],
      encKey: Array[Byte],
      decKey: Array[Byte]
  ): Unit =
    _previousDecKey = Some(_decKey)
    _verifyKey = verifyKey
    _signKey = signKey
    _encKey = encKey
    _decKey = decKey

  override def pseudonym(): String = _pseudonym
  override def verifyKey(): Option[Array[Byte]] = Some(_verifyKey)
  override def signKey(): Array[Byte] = _signKey
  override def encKey(): Option[Array[Byte]] = Some(_encKey)
  override def decKey(): Array[Byte] = _decKey
  override def previousDecKey(): Option[Array[Byte]] = _previousDecKey
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
    assert(alice.verify(message, sig, alice.verifyKey().get))
  }

  test("verify returns false for a tampered message") {
    val alice = newIdentity()
    val message = "hello deposplit".getBytes("UTF-8")
    val sig = alice.sign(message)
    val tampered = "hello depospliz".getBytes("UTF-8")
    assert(!alice.verify(tampered, sig, alice.verifyKey().get))
  }

  test("verify returns false when checked against a different key") {
    val alice = newIdentity()
    val bob = newIdentity()
    val message = "hello deposplit".getBytes("UTF-8")
    val sig = alice.sign(message)
    assert(!bob.verify(message, sig, bob.verifyKey().get))
  }

  // ---------------------------------------------------------------------------
  // generateNewKeyPair() / activateKeyPair() — the identity-regeneration trigger
  // ---------------------------------------------------------------------------

  test("generateNewKeyPair does not touch storage") {
    val alice = newIdentity()
    val originalVerifyKey = alice.verifyKey().get
    val originalEncKey = alice.encKey().get
    val candidate = alice.generateNewKeyPair()
    assert(!candidate.verifyKey.sameElements(originalVerifyKey))
    assert(!candidate.encKey.sameElements(originalEncKey))
    // Unpersisted — the live identity hasn't moved.
    assert(alice.verifyKey().get.sameElements(originalVerifyKey))
    assert(alice.encKey().get.sameElements(originalEncKey))
  }

  test("activateKeyPair persists the new keys and preserves the pseudonym") {
    val alice = newIdentity()
    val candidate = alice.generateNewKeyPair()
    alice.activateKeyPair(candidate)
    assert(alice.verifyKey().get.sameElements(candidate.verifyKey))
    assert(alice.encKey().get.sameElements(candidate.encKey))
    assertEquals(alice.pseudonym(), "test")
  }

  // A share is sealed to whichever encKey the holder advertised at deposit time. If rotating destroyed the matching
  // decKey outright, a holder who rotates between a deposit and their pickup could never collect it — the row would
  // stay pending and every later poll would fail identically.

  test("decrypt falls back to the decKey displaced by the last rotation") {
    val alice = newIdentity()
    val bob = newIdentity()
    val share = "one share".getBytes("UTF-8")
    val sealedToAlicesOldKey = bob.encrypt(share, alice.encKey().get)

    alice.activateKeyPair(alice.generateNewKeyPair())

    assert(alice.decrypt(sealedToAlicesOldKey, bob.encKey().get).sameElements(share))
  }

  test("decrypt does not reach back past one generation") {
    val alice = newIdentity()
    val bob = newIdentity()
    val sealedToAlicesOldestKey = bob.encrypt("one share".getBytes("UTF-8"), alice.encKey().get)

    alice.activateKeyPair(alice.generateNewKeyPair())
    alice.activateKeyPair(alice.generateNewKeyPair())

    // Deliberate: one generation covers the deposit-to-pickup window, and no more key material than that lingers at
    // rest.
    intercept[Exception](alice.decrypt(sealedToAlicesOldestKey, bob.encKey().get))
  }

  test("encrypt never seals under the displaced key") {
    val alice = newIdentity()
    val bob = newIdentity()
    val alicesOldEncKey = alice.encKey().get
    alice.activateKeyPair(alice.generateNewKeyPair())

    val sealed_ = alice.encrypt("outgoing".getBytes("UTF-8"), bob.encKey().get)

    assert(bob.decrypt(sealed_, alice.encKey().get).sameElements("outgoing".getBytes("UTF-8")))
    intercept[Exception](bob.decrypt(sealed_, alicesOldEncKey))
  }

  test("registering a fresh identity drops the retained key") {
    val alice = IdentityService(InMemoryForgettableIdentityStore())
    alice.register("test")
    val bob = newIdentity()
    val sealedToAlicesOldKey = bob.encrypt("one share".getBytes("UTF-8"), alice.encKey().get)
    alice.activateKeyPair(alice.generateNewKeyPair())

    // Registration is a new identity, not a continuation of the old one, so nothing carries over.
    alice.register("test")

    intercept[Exception](alice.decrypt(sealedToAlicesOldKey, bob.encKey().get))
  }

  test("sign after activateKeyPair verifies against the new key not the old") {
    val alice = newIdentity()
    val oldVerifyKey = alice.verifyKey().get
    val candidate = alice.generateNewKeyPair()
    alice.activateKeyPair(candidate)
    val message = "post-rotation message".getBytes("UTF-8")
    val sig = alice.sign(message)
    assert(alice.verify(message, sig, candidate.verifyKey))
    assert(!alice.verify(message, sig, oldVerifyKey))
  }

  // ---------------------------------------------------------------------------
  // encrypt()/decrypt() suite-tag wire format
  // ---------------------------------------------------------------------------

  test("encrypt prepends the current TransportSuite tag and decrypt round-trips") {
    val alice = newIdentity()
    val bob = newIdentity()
    val plaintext = "a share of a secret".getBytes("UTF-8")

    // Alice (sender) encrypts to Bob's public enc key; Bob (recipient) decrypts with his own
    // private key + Alice's public enc key — the same static-static DH shape ShareService uses.
    val ciphertext = alice.encrypt(plaintext, bob.encKey().get)

    assertEquals(ciphertext.head, TransportSuite.current.tag)
    val decrypted = bob.decrypt(ciphertext, alice.encKey().get)
    assert(decrypted.sameElements(plaintext))
  }

  test("decrypt throws UnsupportedTransportSuiteException for an unrecognized suite tag") {
    val alice = newIdentity()
    val bob = newIdentity()
    val ciphertext = alice.encrypt("hi".getBytes("UTF-8"), bob.encKey().get)
    val tampered = Array(0x7f.toByte) ++ ciphertext.drop(1)

    intercept[UnsupportedTransportSuiteException] {
      bob.decrypt(tampered, alice.encKey().get)
    }
  }

  test("decrypt throws UnsupportedTransportSuiteException for empty ciphertext") {
    val alice = newIdentity()
    val bob = newIdentity()

    intercept[UnsupportedTransportSuiteException] {
      bob.decrypt(Array.emptyByteArray, alice.encKey().get)
    }
  }
