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
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import value_objects.svo.KeyPairMaterial
import value_objects.svo.TransportSuite
import value_objects.svo.UnsupportedTransportSuiteException

/** In-memory ForgettableIdentityStore — no file I/O needed for these tests. */
class InMemoryForgettableIdentityStore extends ForgettableIdentityStore:
  private var registered = false
  private var _pseudonym = ""
  private var _verifyKey, _signKey, _encKey, _decKey: Array[Byte] = Array.empty
  private var _previousDecKey: Option[Array[Byte]] = None
  private var _identityCreatedAt: Option[java.time.Instant] = None

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
    // Mirrors the real adapters: registration starts a new identity, rotation continues one.
    _identityCreatedAt = Some(java.time.Instant.now())
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
  override def identityCreatedAt(): Option[java.time.Instant] = _identityCreatedAt
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

    assert(alice.decrypt(sealedToAlicesOldKey).sameElements(share))
  }

  test("decrypt does not reach back past one generation") {
    val alice = newIdentity()
    val bob = newIdentity()
    val sealedToAlicesOldestKey = bob.encrypt("one share".getBytes("UTF-8"), alice.encKey().get)

    alice.activateKeyPair(alice.generateNewKeyPair())
    alice.activateKeyPair(alice.generateNewKeyPair())

    // Deliberate: one generation covers the deposit-to-pickup window, and no more key material than that lingers at
    // rest.
    intercept[Exception](alice.decrypt(sealedToAlicesOldestKey))
  }

  test("sealing involves no key of the sender's own") {
    val alice = newIdentity()
    val bob = newIdentity()
    val sealedBeforeAlicesRotations = alice.encrypt("outgoing".getBytes("UTF-8"), bob.encKey().get)
    alice.activateKeyPair(alice.generateNewKeyPair())
    alice.activateKeyPair(alice.generateNewKeyPair())

    // Bob never needs Alice's key to open it, so however often she rotates, what she sealed opens.
    assert(bob.decrypt(sealedBeforeAlicesRotations).sameElements("outgoing".getBytes("UTF-8")))
  }

  // Only the recipient can open a box, not even the device that sealed it — see docs/security.md.
  test("a sender cannot open a box she sealed for somebody else") {
    val alice = newIdentity()
    val bob = newIdentity()
    val box = alice.encrypt("Bob's share".getBytes("UTF-8"), bob.encKey().get)

    intercept[Exception](alice.decrypt(box))
  }

  test("a box is the tag, the encapsulated key and the ciphertext, with a fresh ephemeral key each time") {
    val alice = newIdentity()
    val bob = newIdentity()
    val plaintext = "shhh".getBytes("UTF-8")
    val first = alice.encrypt(plaintext, bob.encKey().get)
    val second = alice.encrypt(plaintext, bob.encKey().get)

    assertEquals(first.length, 1 + 32 + plaintext.length + 16)
    assert(!first.slice(1, 33).sameElements(second.slice(1, 33)))
  }

  test("registering a fresh identity drops the retained key") {
    val alice = IdentityService(InMemoryForgettableIdentityStore())
    alice.register("test")
    val bob = newIdentity()
    val sealedToAlicesOldKey = bob.encrypt("one share".getBytes("UTF-8"), alice.encKey().get)
    alice.activateKeyPair(alice.generateNewKeyPair())

    // Registration is a new identity, not a continuation of the old one, so nothing carries over.
    alice.register("test")

    intercept[Exception](alice.decrypt(sealedToAlicesOldKey))
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
    val decrypted = bob.decrypt(ciphertext)
    assert(decrypted.sameElements(plaintext))
  }

  test("decrypt throws UnsupportedTransportSuiteException for an unrecognized suite tag") {
    val alice = newIdentity()
    val bob = newIdentity()
    val ciphertext = alice.encrypt("hi".getBytes("UTF-8"), bob.encKey().get)
    val tampered = Array(0x7f.toByte) ++ ciphertext.drop(1)

    intercept[UnsupportedTransportSuiteException] {
      bob.decrypt(tampered)
    }
  }

  test("decrypt throws UnsupportedTransportSuiteException for empty ciphertext") {
    val alice = newIdentity()
    val bob = newIdentity()

    intercept[UnsupportedTransportSuiteException] {
      bob.decrypt(Array.emptyByteArray)
    }
  }

  // Sealed labels — the same box as a share, under a key derivation of its own.

  test("sealLabel then openLabel round-trips") {
    val alice = newIdentity()
    val bob = newIdentity()
    val box = alice.sealLabel("Bitcoin seed 🔑", bob.encKey().get)

    assertEquals(box.head, TransportSuite.current.tag)
    assertEquals(bob.openLabel(box), "Bitcoin seed 🔑")
  }

  test("a sealed label does not carry its text in the clear") {
    val alice = newIdentity()
    val bob = newIdentity()
    val box = alice.sealLabel("Bitcoin seed", bob.encKey().get)

    assert(box.indexOfSlice("Bitcoin seed".getBytes("UTF-8").toSeq) < 0)
  }

  test("a share box does not open as a label nor a label box as a share") {
    val alice = newIdentity()
    val bob = newIdentity()
    val shareBox = alice.encrypt("one share".getBytes("UTF-8"), bob.encKey().get)
    val labelBox = alice.sealLabel("one label", bob.encKey().get)

    intercept[Exception](bob.openLabel(shareBox))
    intercept[Exception](bob.decrypt(labelBox))
  }

  test("openLabel falls back to the decKey displaced by the last rotation") {
    val alice = newIdentity()
    val bob = newIdentity()
    val sealedToAlicesOldKey = bob.sealLabel("passport", alice.encKey().get)

    alice.activateKeyPair(alice.generateNewKeyPair())

    assertEquals(alice.openLabel(sealedToAlicesOldKey), "passport")
  }

  // Cross-platform box vectors — the same four boxes, sealed once by CryptoKit and once by BouncyCastle to one fixed
  // recipient key, are opened by iOS, Android and phon alike. HPKE draws a fresh ephemeral key per box, so a box cannot
  // be reproduced byte for byte; what each platform proves is that it opens what the other library sealed. Identical
  // fixtures are checked into iOS's IdentityServiceVerifyTests.swift and Android's IdentityServiceVerifyTest.kt.

  private def hex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private val vectorRecipientDecKey = Array.tabulate[Byte](32)(i => (0x20 + i).toByte)
  private val vectorRecipientEncKey = hex("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254")
  private val cryptoKitShareBox = hex(
    "028645dabe9fc9135324fc4b60c60d562a5b91cbba3a4e7767363138c411154465cfaff103793c5427240d1c797a5c7f3019b453158117d32c409c073aa86d828ce754b2"
  )
  private val cryptoKitLabelBox = hex(
    "02182112460138f7f6314a553918a4f46d85148ccd8a7ab322c0cb2eb34ac97f528f0673b03dac84898ee14fbc6cff3c2318acb1fe4ef3d036a797f294ce8cef4274"
  )
  private val bouncyCastleShareBox = hex(
    "02b09f312abb31cbf78fc7c41f3912006d25d966d2be78a376bd388cdd30e685446a75dadf1450dade7e955dd6879fa1e9fd1aa7e62e721912fa5bcec7854e7397b80767"
  )
  private val bouncyCastleLabelBox = hex(
    "02e859635cccd28e42b3f26bfacdf98db69242fd81c5faa67dec62de46714b270f48bdce7f66c707226e73396b84cb1d9fa4e5b9cf7b008df9fed2163a14f8be83fc"
  )

  private def vectorRecipient(): IdentityService =
    val recipient = newIdentity()
    val current = recipient.generateNewKeyPair()
    recipient.activateKeyPair(
      KeyPairMaterial(current.verifyKey, current.signKey, vectorRecipientEncKey, vectorRecipientDecKey)
    )
    recipient

  test("the fixed recipient key derives the fixed public key") {
    val derived = X25519PrivateKeyParameters(vectorRecipientDecKey, 0).generatePublicKey().getEncoded
    assert(derived.sameElements(vectorRecipientEncKey))
  }

  test("the CryptoKit vector boxes open") {
    val recipient = vectorRecipient()
    assertEquals(String(recipient.decrypt(cryptoKitShareBox), "UTF-8"), "a share of a secret")
    assertEquals(recipient.openLabel(cryptoKitLabelBox), "Bitcoin seed 🔑")
  }

  test("the BouncyCastle vector boxes open") {
    val recipient = vectorRecipient()
    assertEquals(String(recipient.decrypt(bouncyCastleShareBox), "UTF-8"), "a share of a secret")
    assertEquals(recipient.openLabel(bouncyCastleLabelBox), "Bitcoin seed 🔑")
  }
