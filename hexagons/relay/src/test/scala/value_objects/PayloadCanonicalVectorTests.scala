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

package value_objects

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Cross-platform interop vector for `PayloadCanonical.forOpen`'s byte construction — mirrors the existing hand-derived
  * SSS test vectors (`ShamirTest.kt` / `ShamirSecretSharingTests.swift`). Ed25519 sign/verify interop across
  * BouncyCastle/CryptoKit is already proven via the transport-auth signature; what this vector actually exercises is
  * the *canonical byte construction* itself — a field-order or encoding slip on any one platform would silently produce
  * a different signature than the other three even though each platform's own sign/verify round-trips fine internally.
  *
  * Identical fixed inputs, keypair, and expected outputs are checked into
  * `hexagons/phon/src/test/scala/value_objects/svo/PayloadCanonicalVectorTests.scala`,
  * `Android/hexagon/src/test/kotlin/com/deposplit/value_objects/PayloadCanonicalVectorTest.kt` and
  * `iOS/hexagon/Tests/PayloadCanonicalVectorTests.swift`. All four must produce byte-identical canonical bytes and the
  * same signature for the same 32-byte private key seed.
  */
class PayloadCanonicalVectorTests extends munit.FunSuite:

  private val b64url = Base64.getUrlEncoder.withoutPadding

  // Private key seed: bytes 0x00..0x1f. Not a real identity — a fixed, reproducible fixture.
  private val privateKeySeed: Array[Byte] = (0 until 32).map(_.toByte).toArray
  private val expectedPublicKeyBase64Url = "A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg"
  private val expectedSignatureBase64Url =
    "WFKVgN38zr_3fiLZ1UpxnrvUoW0KA-XjD1ml-VyfITDuCMv9D9uT0ryaHCiHYtWc9_rSpOKDw4kjbtqHMRPwBA"

  private val secretId = SecretId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val recipientKey = PublicKey.fromBytes(Array.fill(32)(0x02.toByte)).getOrElse(fail("bad fixture key"))
  private val label = Label("cross-platform test vector")
  private val secretCreatedAt = Instant.parse("2026-01-01T00:00:00Z")
  private val ciphertext: Array[Byte] = Array[Byte](1, 2, 3, 4, 5)
  // k/n, then mimeType — each appended at the end of the field sequence in turn, so the fields
  // above are byte-identical to the vector that predates them.
  private val k = Some(2)
  private val n = Some(3)
  private val mimeType = Some(MimeType("text/plain"))

  test("forOpen produces the fixed canonical bytes") {
    val canon = PayloadCanonical.forOpen(
      secretId,
      ShareTransactionType.Deposit,
      recipientKey,
      label,
      secretCreatedAt,
      Some(ciphertext),
      k,
      n,
      mimeType
    )
    val expected =
      "11111111-1111-1111-1111-111111111111\ndeposit\nAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\ncross-platform test vector\n1767225600000\nAQIDBAU=\n2\n3\ntext/plain"
    assertEquals(new String(canon, "UTF-8"), expected)
  }

  test("signing the canonical bytes with the fixed seed reproduces the fixed signature") {
    val canon = PayloadCanonical.forOpen(
      secretId,
      ShareTransactionType.Deposit,
      recipientKey,
      label,
      secretCreatedAt,
      Some(ciphertext),
      k,
      n,
      mimeType
    )
    val privKey = Ed25519PrivateKeyParameters(privateKeySeed, 0)
    val pubKey = privKey.generatePublicKey()
    assertEquals(b64url.encodeToString(pubKey.getEncoded), expectedPublicKeyBase64Url)

    val signer = Ed25519Signer()
    signer.init(true, privKey)
    signer.update(canon, 0, canon.length)
    val sig = signer.generateSignature()
    assertEquals(b64url.encodeToString(sig), expectedSignatureBase64Url)
  }

  test("the fixed signature verifies against the fixed public key via PublicKey.verify") {
    val canon = PayloadCanonical.forOpen(
      secretId,
      ShareTransactionType.Deposit,
      recipientKey,
      label,
      secretCreatedAt,
      Some(ciphertext),
      k,
      n,
      mimeType
    )
    val pk = PublicKey.fromBase64Url(expectedPublicKeyBase64Url).getOrElse(fail("bad fixture key"))
    val sig = Signature.fromBase64Url(expectedSignatureBase64Url).getOrElse(fail("bad fixture signature"))
    assert(pk.verify(canon, sig))
  }

  // ---------------------------------------------------------------------------
  // forRotation — same cross-platform-interop purpose as forOpen above, checked into
  // Android's and iOS's PayloadCanonicalVectorTest/-Tests once their turn comes. Uses the same
  // fixed private key seed as forOpen so both vectors are anchored to one known keypair.
  // ---------------------------------------------------------------------------

  private val rotationRecipientKey = PublicKey.fromBytes(Array.fill(32)(0x03.toByte)).getOrElse(fail("bad fixture key"))
  private val newVerifyKey = PublicKey.fromBytes(Array.fill(32)(0x04.toByte)).getOrElse(fail("bad fixture key"))
  private val newEncKey = X25519Key.fromBytes(Array.fill(32)(0x05.toByte)).getOrElse(fail("bad fixture key"))
  // Appended as a 4th line; the fields above are byte-identical to the original vector.
  private val newCipherSuite = CipherSuite.current
  private val expectedRotationSignatureBase64Url =
    "EH45bL4chGQALZ6J9IDhfUAtPNovGHmqlJvF6HBKa8sqkF3SU1NhMGWmSTGM87isxdHIxoQCHFITplmzN1zeDg"

  test("forRotation produces the fixed canonical bytes") {
    val canon = PayloadCanonical.forRotation(rotationRecipientKey, newVerifyKey, newEncKey, newCipherSuite)
    val expected =
      "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM\nBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ\nBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU\ned25519+x25519-v1"
    assertEquals(new String(canon, "UTF-8"), expected)
  }

  test("signing the rotation canonical bytes with the fixed seed reproduces the fixed signature") {
    val canon = PayloadCanonical.forRotation(rotationRecipientKey, newVerifyKey, newEncKey, newCipherSuite)
    val privKey = Ed25519PrivateKeyParameters(privateKeySeed, 0)
    val signer = Ed25519Signer()
    signer.init(true, privKey)
    signer.update(canon, 0, canon.length)
    val sig = signer.generateSignature()
    assertEquals(b64url.encodeToString(sig), expectedRotationSignatureBase64Url)
  }

  test("the fixed rotation signature verifies against the fixed public key via PublicKey.verify") {
    val canon = PayloadCanonical.forRotation(rotationRecipientKey, newVerifyKey, newEncKey, newCipherSuite)
    val pk = PublicKey.fromBase64Url(expectedPublicKeyBase64Url).getOrElse(fail("bad fixture key"))
    val sig = Signature.fromBase64Url(expectedRotationSignatureBase64Url).getOrElse(fail("bad fixture signature"))
    assert(pk.verify(canon, sig))
  }

  // ---------------------------------------------------------------------------
  // forHeartbeat — same cross-platform-interop purpose as forOpen/forRotation above,
  // checked into Android's and iOS's PayloadCanonicalVectorTest/-Tests once their turn comes.
  // Uses the same fixed private key seed as forOpen/forRotation so all three vectors are anchored
  // to one known keypair.
  // ---------------------------------------------------------------------------

  private val heartbeatOwnerKey = PublicKey.fromBytes(Array.fill(32)(0x06.toByte)).getOrElse(fail("bad fixture key"))
  // Deliberately out of sorted order in the fixture to prove forHeartbeat sorts before joining —
  // a naive pass-through would silently disagree with a platform that assembled the list differently.
  private val heartbeatSecretIds = Seq(
    UUID.fromString("33333333-3333-3333-3333-333333333333"),
    UUID.fromString("11111111-1111-1111-1111-111111111111"),
    UUID.fromString("22222222-2222-2222-2222-222222222222")
  )
  private val expectedHeartbeatSignatureBase64Url =
    "w6fmGn4t7y2RSNakPBzi57H40u5kJI6CZAhEGdzLBOwZd__jabsge2tEmIpczMqEd3ODpNUJ72Ww2KEe8LYQCw"

  test("forHeartbeat produces the fixed canonical bytes, sorted regardless of input order") {
    val canon = PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, optedOut = false)
    val expected =
      "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY\n11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222,33333333-3333-3333-3333-333333333333\nfalse"
    assertEquals(new String(canon, "UTF-8"), expected)
  }

  test("signing the heartbeat canonical bytes with the fixed seed reproduces the fixed signature") {
    val canon = PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, optedOut = false)
    val privKey = Ed25519PrivateKeyParameters(privateKeySeed, 0)
    val signer = Ed25519Signer()
    signer.init(true, privKey)
    signer.update(canon, 0, canon.length)
    val sig = signer.generateSignature()
    assertEquals(b64url.encodeToString(sig), expectedHeartbeatSignatureBase64Url)
  }

  test("the fixed heartbeat signature verifies against the fixed public key via PublicKey.verify") {
    val canon = PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, optedOut = false)
    val pk = PublicKey.fromBase64Url(expectedPublicKeyBase64Url).getOrElse(fail("bad fixture key"))
    val sig = Signature.fromBase64Url(expectedHeartbeatSignatureBase64Url).getOrElse(fail("bad fixture signature"))
    assert(pk.verify(canon, sig))
  }
