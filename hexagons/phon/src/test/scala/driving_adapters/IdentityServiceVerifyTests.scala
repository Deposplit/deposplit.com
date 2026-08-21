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
  override def edPublicKey(): Array[Byte] = _edPk
  override def edPrivateKey(): Array[Byte] = _edSk
  override def xPublicKey(): Array[Byte] = _xPk
  override def xPrivateKey(): Array[Byte] = _xSk
  override def forget(): Unit = registered = false

/** Mirrors hexagons/relay's PublicKeyTests valid/tampered/wrong-key trio, for `IdentityService.verify`
  * — the phon-side counterpart of the server's `PublicKey.verify`, used to independently re-verify
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
    assert(alice.verify(message, sig, alice.edPublicKey()))
  }

  test("verify returns false for a tampered message") {
    val alice = newIdentity()
    val message = "hello deposplit".getBytes("UTF-8")
    val sig = alice.sign(message)
    val tampered = "hello depospliz".getBytes("UTF-8")
    assert(!alice.verify(tampered, sig, alice.edPublicKey()))
  }

  test("verify returns false when checked against a different key") {
    val alice = newIdentity()
    val bob = newIdentity()
    val message = "hello deposplit".getBytes("UTF-8")
    val sig = alice.sign(message)
    assert(!bob.verify(message, sig, bob.edPublicKey()))
  }

  // ---------------------------------------------------------------------------
  // generateNewKeyPair() / activateKeyPair() — item 9's identity-regen trigger
  // ---------------------------------------------------------------------------

  test("generateNewKeyPair does not touch storage") {
    val alice = newIdentity()
    val originalEdKey = alice.edPublicKey()
    val originalXKey = alice.xPublicKey()
    val candidate = alice.generateNewKeyPair()
    assert(!candidate.edPublicKey.sameElements(originalEdKey))
    assert(!candidate.xPublicKey.sameElements(originalXKey))
    // Unpersisted — the live identity hasn't moved.
    assert(alice.edPublicKey().sameElements(originalEdKey))
    assert(alice.xPublicKey().sameElements(originalXKey))
  }

  test("activateKeyPair persists the new keys and preserves the pseudonym") {
    val alice = newIdentity()
    val candidate = alice.generateNewKeyPair()
    alice.activateKeyPair(candidate)
    assert(alice.edPublicKey().sameElements(candidate.edPublicKey))
    assert(alice.xPublicKey().sameElements(candidate.xPublicKey))
    assertEquals(alice.pseudonym(), "test")
  }

  test("sign after activateKeyPair verifies against the new key not the old") {
    val alice = newIdentity()
    val oldEdKey = alice.edPublicKey()
    val candidate = alice.generateNewKeyPair()
    alice.activateKeyPair(candidate)
    val message = "post-rotation message".getBytes("UTF-8")
    val sig = alice.sign(message)
    assert(alice.verify(message, sig, candidate.edPublicKey))
    assert(!alice.verify(message, sig, oldEdKey))
  }
