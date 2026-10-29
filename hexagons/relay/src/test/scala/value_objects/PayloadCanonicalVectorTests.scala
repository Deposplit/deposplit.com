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
    "9E9otlaDVTPp1kcFbjfffN9ycBws7kuU1LaIMmBZQQ8ydtiLIJB8y_zptry8J1M9CN2OdoRiJazdY_nKFiJuBQ"

  private val secretId = SecretId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val recipientKey = PublicKey.fromBytes(Array.fill(32)(0x02.toByte)).getOrElse(fail("bad fixture key"))
  // Any non-empty bytes will do: this vector checks the canonical construction, not the box that seals a label.
  private val sealedLabel = SealedLabel.fromBytes(Array[Byte](6, 7, 8, 9, 10)).getOrElse(fail("bad fixture label"))
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
      sealedLabel,
      secretCreatedAt,
      Some(ciphertext),
      k,
      n,
      mimeType
    )
    val expected =
      "11111111-1111-1111-1111-111111111111\ndeposit\nAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\nBgcICQo=\n1767225600000\nAQIDBAU=\n2\n3\ntext/plain"
    assertEquals(new String(canon, "UTF-8"), expected)
  }

  test("signing the canonical bytes with the fixed seed reproduces the fixed signature") {
    val canon = PayloadCanonical.forOpen(
      secretId,
      ShareTransactionType.Deposit,
      recipientKey,
      sealedLabel,
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
      sealedLabel,
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
  // forOpen for a withdrawal — the same construction with every optional field absent, which is
  // the shape no other vector covers: four trailing empty lines, and a transaction type that
  // exists only in the wire value being signed.
  // ---------------------------------------------------------------------------

  private val expectedWithdrawalSignatureBase64Url =
    "1WwboKOifAfmm5c851r1hX3CGjjPWidRM5yox7K5MgGx1_XKgMV23TXDBTJIud4Ob2fMLziTrqih3hsEMDyKAQ"

  private def withdrawalCanon: Array[Byte] =
    PayloadCanonical.forOpen(
      secretId,
      ShareTransactionType.Withdrawal,
      recipientKey,
      sealedLabel,
      secretCreatedAt,
      None
    )

  test("forOpen for a withdrawal produces the fixed canonical bytes") {
    val expected =
      "11111111-1111-1111-1111-111111111111\nwithdrawal\nAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\nBgcICQo=\n1767225600000\n\n\n\n"
    assertEquals(new String(withdrawalCanon, "UTF-8"), expected)
  }

  test("signing the withdrawal canonical bytes with the fixed seed reproduces the fixed signature") {
    val canon = withdrawalCanon
    val privKey = Ed25519PrivateKeyParameters(privateKeySeed, 0)
    val signer = Ed25519Signer()
    signer.init(true, privKey)
    signer.update(canon, 0, canon.length)
    val sig = signer.generateSignature()
    assertEquals(b64url.encodeToString(sig), expectedWithdrawalSignatureBase64Url)
  }

  test("the fixed withdrawal signature verifies against the fixed public key via PublicKey.verify") {
    val pk = PublicKey.fromBase64Url(expectedPublicKeyBase64Url).getOrElse(fail("bad fixture key"))
    val sig = Signature.fromBase64Url(expectedWithdrawalSignatureBase64Url).getOrElse(fail("bad fixture signature"))
    assert(pk.verify(withdrawalCanon, sig))
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
  // Appended as a 4th line, with milliseconds that are not zero, so a platform that drops them
  // on the way through cannot pass by accident.
  private val heartbeatEmittedAt = Instant.parse("2026-01-02T03:04:05.678Z")
  private val expectedHeartbeatSignatureBase64Url =
    "KP2laDBXpS-Bp_3MzghlBsGjKKxWetsdDnzgbygTNu-A7-g9Dl4vvrsy3hnuOccsGHMTdxJtbGWKwDWuJweXBw"

  test("forHeartbeat produces the fixed canonical bytes, sorted regardless of input order") {
    val canon =
      PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, optedOut = false, heartbeatEmittedAt)
    val expected =
      "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY\n11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222,33333333-3333-3333-3333-333333333333\nfalse\n1767323045678"
    assertEquals(new String(canon, "UTF-8"), expected)
  }

  test("signing the heartbeat canonical bytes with the fixed seed reproduces the fixed signature") {
    val canon =
      PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, optedOut = false, heartbeatEmittedAt)
    val privKey = Ed25519PrivateKeyParameters(privateKeySeed, 0)
    val signer = Ed25519Signer()
    signer.init(true, privKey)
    signer.update(canon, 0, canon.length)
    val sig = signer.generateSignature()
    assertEquals(b64url.encodeToString(sig), expectedHeartbeatSignatureBase64Url)
  }

  test("the fixed heartbeat signature verifies against the fixed public key via PublicKey.verify") {
    val canon =
      PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, optedOut = false, heartbeatEmittedAt)
    val pk = PublicKey.fromBase64Url(expectedPublicKeyBase64Url).getOrElse(fail("bad fixture key"))
    val sig = Signature.fromBase64Url(expectedHeartbeatSignatureBase64Url).getOrElse(fail("bad fixture signature"))
    assert(pk.verify(canon, sig))
  }

  // ── Wire timestamps ────────────────────────────────────────────────────────
  // The same table is checked into phon's, Android's and iOS's suites, so all four read any timestamp's text to the
  // same millisecond and write that millisecond back the same way.

  private val wireInstants = Seq(
    ("2026-10-29T07:27:35Z", 1793258855000L, "2026-10-29T07:27:35.000Z"),
    ("2026-10-29T07:27:35.457Z", 1793258855457L, "2026-10-29T07:27:35.457Z"),
    ("2026-10-29T07:27:35.457123Z", 1793258855457L, "2026-10-29T07:27:35.457Z"),
    ("2026-10-29T07:27:35.457999999Z", 1793258855457L, "2026-10-29T07:27:35.457Z"),
    ("2026-10-29T07:27:35.999999999Z", 1793258855999L, "2026-10-29T07:27:35.999Z")
  )

  test("a wire timestamp is read to the millisecond, cut and never rounded") {
    wireInstants.foreach((text, epochMs, _) =>
      assertEquals(PayloadCanonical.parseWireInstant(text), Some(Instant.ofEpochMilli(epochMs)), text)
    )
  }

  test("a wire timestamp is written with exactly the three fractional digits a signature covers") {
    wireInstants.foreach((text, _, written) =>
      assertEquals(PayloadCanonical.wireInstant(Instant.parse(text)), written, text)
    )
  }

  test("a wire timestamp that is not ISO-8601 is not read") {
    assertEquals(PayloadCanonical.parseWireInstant("2026-10-29 07:27:35.457"), None)
    assertEquals(PayloadCanonical.parseWireInstant(""), None)
  }
