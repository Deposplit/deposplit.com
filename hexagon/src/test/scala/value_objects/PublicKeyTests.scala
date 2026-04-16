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

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.{
  Ed25519KeyGenerationParameters,
  Ed25519PrivateKeyParameters,
  Ed25519PublicKeyParameters
}
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

class PublicKeyTests extends munit.FunSuite:

  private val encoder = Base64.getUrlEncoder.withoutPadding

  // Generates a fresh Ed25519 keypair; returns (PublicKey, raw private key bytes).
  private def generateKeyPair(): (PublicKey, Array[Byte]) =
    val gen = Ed25519KeyPairGenerator()
    gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
    val pair = gen.generateKeyPair()
    val pubBytes = pair.getPublic.asInstanceOf[Ed25519PublicKeyParameters].getEncoded
    val privBytes = pair.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters].getEncoded
    val publicKey = PublicKey.fromBase64Url(encoder.encodeToString(pubBytes)).getOrElse(fail("keypair setup failed"))
    (publicKey, privBytes)

  private def sign(privateKeyBytes: Array[Byte], message: Array[Byte]): Signature =
    val signer = Ed25519Signer()
    signer.init(true, new Ed25519PrivateKeyParameters(privateKeyBytes, 0))
    signer.update(message, 0, message.length)
    val sigBytes = signer.generateSignature()
    Signature.fromBase64Url(encoder.encodeToString(sigBytes)).getOrElse(fail("sign failed"))

  // --- Parsing ---

  test("invalid base64url is rejected") {
    assert(PublicKey.fromBase64Url("not!!valid@@base64").isLeft)
  }

  test("fewer than 32 bytes is rejected") {
    val short = encoder.encodeToString(Array.fill(16)(0x01.toByte))
    assert(PublicKey.fromBase64Url(short).isLeft)
  }

  test("more than 32 bytes is rejected") {
    val long = encoder.encodeToString(Array.fill(33)(0x01.toByte))
    assert(PublicKey.fromBase64Url(long).isLeft)
  }

  test("exactly 32 bytes is accepted") {
    val valid = encoder.encodeToString(Array.fill(32)(0x02.toByte))
    assert(PublicKey.fromBase64Url(valid).isRight)
  }

  test("toBase64Url round-trips") {
    val bytes = Array.fill(32)(0x03.toByte)
    val b64 = encoder.encodeToString(bytes)
    val pk = PublicKey.fromBase64Url(b64).getOrElse(fail("parse failed"))
    assertEquals(pk.toBase64Url, b64)
  }

  // --- Signature verification ---

  test("verify returns true for a valid signature") {
    val (pk, privBytes) = generateKeyPair()
    val message = "hello deposplit".getBytes("UTF-8")
    val signature = sign(privBytes, message)
    assert(pk.verify(message, signature))
  }

  test("verify returns false for a tampered message") {
    val (pk, privBytes) = generateKeyPair()
    val message = "hello deposplit".getBytes("UTF-8")
    val signature = sign(privBytes, message)
    val tampered = "hello depospliz".getBytes("UTF-8")
    assert(!pk.verify(tampered, signature))
  }

  test("verify returns false for a different key") {
    val (pk, _) = generateKeyPair()
    val (_, privBytes2) = generateKeyPair()
    val message = "hello deposplit".getBytes("UTF-8")
    val signature = sign(privBytes2, message)
    assert(!pk.verify(message, signature))
  }
