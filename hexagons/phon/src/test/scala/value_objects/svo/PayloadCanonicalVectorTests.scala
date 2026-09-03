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

package value_objects.svo

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

import java.time.Instant
import java.util.Base64
import java.util.UUID

/** phon's copy of the cross-platform interop vector for `PayloadCanonical` — the fourth independent implementation of
  * the signed byte sequence, alongside `hexagons/relay`'s, Android's and iOS's.
  *
  * The fixed inputs, keypair and expected outputs below are the same ones checked into
  * `hexagons/relay/src/test/scala/value_objects/PayloadCanonicalVectorTests.scala`,
  * `Android/hexagon/src/test/kotlin/com/deposplit/value_objects/PayloadCanonicalVectorTest.kt` and
  * `iOS/hexagon/Tests/PayloadCanonicalVectorTests.swift`. All four must produce byte-identical canonical bytes and the
  * same signature for the same 32-byte private key seed. `ShareServiceSignatureTests` exercises these constructions
  * structurally — signatures made here round-trip here — which is exactly the check that cannot notice phon drifting
  * away from the other three in lockstep with itself.
  *
  * This is a port rather than a copy: phon's `PayloadCanonical` takes `UUID`/`Array[Byte]`/`String` where the relay's
  * takes `SecretId`/`PublicKey`/`Label`. The bytes it must produce are identical regardless.
  *
  * The relay's file has a third test per construction, checking the fixed signature back through `PublicKey.verify`.
  * That is not ported: phon has no `PublicKey` value object, and its verification path is `IdentityService.verify`,
  * already covered valid/tampered/wrong-key by `IdentityServiceVerifyTests`.
  */
class PayloadCanonicalVectorTests extends munit.FunSuite:

  private val b64url = Base64.getUrlEncoder.withoutPadding

  // Private key seed: bytes 0x00..0x1f. Not a real identity — a fixed, reproducible fixture.
  private val privateKeySeed: Array[Byte] = (0 until 32).map(_.toByte).toArray
  private val expectedPublicKeyBase64Url = "A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg"

  private def sign(canon: Array[Byte]): String =
    val signer = Ed25519Signer()
    signer.init(true, Ed25519PrivateKeyParameters(privateKeySeed, 0))
    signer.update(canon, 0, canon.length)
    b64url.encodeToString(signer.generateSignature())

  test("the fixed seed derives the fixed public key") {
    val pubKey = Ed25519PrivateKeyParameters(privateKeySeed, 0).generatePublicKey()
    assertEquals(b64url.encodeToString(pubKey.getEncoded), expectedPublicKeyBase64Url)
  }

  // ---------------------------------------------------------------------------
  // forOpen
  // ---------------------------------------------------------------------------

  private val secretId = UUID.fromString("11111111-1111-1111-1111-111111111111")
  private val recipientKey: Array[Byte] = Array.fill(32)(0x02.toByte)
  private val label = "cross-platform test vector"
  private val secretCreatedAt = Instant.parse("2026-01-01T00:00:00Z")
  private val ciphertext: Array[Byte] = Array[Byte](1, 2, 3, 4, 5)
  // k/n, then mimeType — each appended at the end of the field sequence in turn, so the fields
  // above are byte-identical to the vector that predates them.
  private val k = Some(2)
  private val n = Some(3)
  private val mimeType = Some(MimeType("text/plain"))
  private val expectedSignatureBase64Url =
    "WFKVgN38zr_3fiLZ1UpxnrvUoW0KA-XjD1ml-VyfITDuCMv9D9uT0ryaHCiHYtWc9_rSpOKDw4kjbtqHMRPwBA"

  private def openCanonical(): Array[Byte] =
    PayloadCanonical.forOpen(
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

  test("forOpen produces the fixed canonical bytes") {
    val expected =
      "11111111-1111-1111-1111-111111111111\ndeposit\nAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\ncross-platform test vector\n1767225600000\nAQIDBAU=\n2\n3\ntext/plain"
    assertEquals(new String(openCanonical(), "UTF-8"), expected)
  }

  test("signing the canonical bytes with the fixed seed reproduces the fixed signature") {
    assertEquals(sign(openCanonical()), expectedSignatureBase64Url)
  }

  // ---------------------------------------------------------------------------
  // forRotation — uses the same fixed private key seed as forOpen so both vectors are anchored to
  // one known keypair.
  // ---------------------------------------------------------------------------

  private val rotationRecipientKey: Array[Byte] = Array.fill(32)(0x03.toByte)
  private val newVerifyKey: Array[Byte] = Array.fill(32)(0x04.toByte)
  private val newEncKey: Array[Byte] = Array.fill(32)(0x05.toByte)
  // Appended as a 4th line; the fields above are byte-identical to the original vector.
  private val newCipherSuite = CipherSuite.current
  private val expectedRotationSignatureBase64Url =
    "EH45bL4chGQALZ6J9IDhfUAtPNovGHmqlJvF6HBKa8sqkF3SU1NhMGWmSTGM87isxdHIxoQCHFITplmzN1zeDg"

  private def rotationCanonical(): Array[Byte] =
    PayloadCanonical.forRotation(rotationRecipientKey, newVerifyKey, newEncKey, newCipherSuite)

  test("forRotation produces the fixed canonical bytes") {
    val expected =
      "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM\nBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ\nBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU\ned25519+x25519-v1"
    assertEquals(new String(rotationCanonical(), "UTF-8"), expected)
  }

  test("signing the rotation canonical bytes with the fixed seed reproduces the fixed signature") {
    assertEquals(sign(rotationCanonical()), expectedRotationSignatureBase64Url)
  }

  // ---------------------------------------------------------------------------
  // forHeartbeat — same fixed private key seed again.
  // ---------------------------------------------------------------------------

  private val heartbeatOwnerKey: Array[Byte] = Array.fill(32)(0x06.toByte)
  // Deliberately out of sorted order in the fixture to prove forHeartbeat sorts before joining —
  // a naive pass-through would silently disagree with a platform that assembled the list differently.
  private val heartbeatSecretIds = Seq(
    UUID.fromString("33333333-3333-3333-3333-333333333333"),
    UUID.fromString("11111111-1111-1111-1111-111111111111"),
    UUID.fromString("22222222-2222-2222-2222-222222222222")
  )
  private val expectedHeartbeatSignatureBase64Url =
    "w6fmGn4t7y2RSNakPBzi57H40u5kJI6CZAhEGdzLBOwZd__jabsge2tEmIpczMqEd3ODpNUJ72Ww2KEe8LYQCw"

  private def heartbeatCanonical(): Array[Byte] =
    PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, optedOut = false)

  test("forHeartbeat produces the fixed canonical bytes, sorted regardless of input order") {
    val expected =
      "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY\n11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222,33333333-3333-3333-3333-333333333333\nfalse"
    assertEquals(new String(heartbeatCanonical(), "UTF-8"), expected)
  }

  test("signing the heartbeat canonical bytes with the fixed seed reproduces the fixed signature") {
    assertEquals(sign(heartbeatCanonical()), expectedHeartbeatSignatureBase64Url)
  }
