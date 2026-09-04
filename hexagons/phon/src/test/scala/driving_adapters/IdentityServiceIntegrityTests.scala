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
import value_objects.svo.IdentityIntegrity
import value_objects.svo.IdentityStorageUnavailableException

/** A store that can be put into the states a phone switch produces on a real device: app files restored, private key
  * material gone or belonging to some other identity, or readable only once the device is unlocked. phon itself cannot
  * reach those states — it keeps keys and state in the same files — but the domain logic is shared with the mobile
  * apps, so it is tested here too.
  */
class RestorableIdentityStore extends ForgettableIdentityStore:
  private var registered = false
  private var _pseudonym = ""
  private var _verifyKey, _signKey, _encKey, _decKey: Array[Byte] = Array.empty
  private var _previousDecKey: Option[Array[Byte]] = None
  private var _identityCreatedAt: Option[java.time.Instant] = None

  /** Thrown by every private-key read, standing in for key storage that no longer yields its contents. */
  var privateKeyFailure: Option[Exception] = None

  /** Key storage that is locked hides the public keys as well, not only the private ones. */
  var publicKeysReadable = true

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

  /** Leaves the public keys where they are and swaps the private halves for another identity's. */
  def replacePrivateKeysWith(other: RestorableIdentityStore): Unit =
    _signKey = other._signKey
    _decKey = other._decKey

  override def pseudonym(): String = _pseudonym
  override def verifyKey(): Option[Array[Byte]] = Option.when(publicKeysReadable)(_verifyKey)
  override def signKey(): Array[Byte] = privateKeyFailure.fold(_signKey)(throw _)
  override def encKey(): Option[Array[Byte]] = Option.when(publicKeysReadable)(_encKey)
  override def decKey(): Array[Byte] = privateKeyFailure.fold(_decKey)(throw _)
  override def previousDecKey(): Option[Array[Byte]] = _previousDecKey
  override def identityCreatedAt(): Option[java.time.Instant] = _identityCreatedAt
  override def forget(): Unit = registered = false

class IdentityServiceIntegrityTests extends munit.FunSuite:

  private def registered(): (IdentityService, RestorableIdentityStore) =
    val store = RestorableIdentityStore()
    val svc = IdentityService(store)
    svc.register("test")
    (svc, store)

  test("a device that just registered is intact") {
    val (svc, _) = registered()
    assertEquals(svc.integrity(), IdentityIntegrity.Intact)
  }

  test("an unregistered device is intact, having nothing to have lost") {
    val svc = IdentityService(RestorableIdentityStore())
    assertEquals(svc.integrity(), IdentityIntegrity.Intact)
  }

  test("a rotation leaves the identity intact") {
    val (svc, _) = registered()
    svc.activateKeyPair(svc.generateNewKeyPair())
    assertEquals(svc.integrity(), IdentityIntegrity.Intact)
  }

  // The restore case: app storage came across, key storage did not.
  test("private keys that no longer read are keys lost") {
    val (svc, store) = registered()
    store.privateKeyFailure = Some(IllegalStateException("key storage no longer yields its contents"))
    assertEquals(svc.integrity(), IdentityIntegrity.KeysLost)
  }

  // The Android restore in particular: the public keys are stored in the clear beside the wrapped private halves, so
  // they come back intact and the device advertises keys it cannot use.
  test("public keys that outlive their private halves are keys lost") {
    val (svc, store) = registered()
    val (_, someoneElse) = registered()
    store.replacePrivateKeysWith(someoneElse)
    assertEquals(svc.integrity(), IdentityIntegrity.KeysLost)
  }

  // A locked device must never be mistaken for an emptied one — KeysLost is what offers to mint a replacement
  // identity over the top.
  test("key storage that is merely locked is unreadable, not lost") {
    val (svc, store) = registered()
    store.privateKeyFailure = Some(IdentityStorageUnavailableException("device is locked"))
    assertEquals(svc.integrity(), IdentityIntegrity.Unreadable)
  }

  // Locked storage hides the public keys too, and they are optional — so the probe has to read the private halves
  // first. Reading the public ones first would call this device emptied and offer it a replacement identity over a
  // working one.
  test("locked storage is unreadable even when the public keys are hidden as well") {
    val (svc, store) = registered()
    store.privateKeyFailure = Some(IdentityStorageUnavailableException("device is locked"))
    store.publicKeysReadable = false
    assertEquals(svc.integrity(), IdentityIntegrity.Unreadable)
  }

  test("public keys that are simply gone are keys lost") {
    val (svc, store) = registered()
    store.publicKeysReadable = false
    assertEquals(svc.integrity(), IdentityIntegrity.KeysLost)
  }
