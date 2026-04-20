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

package api

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.apache.pekko.util.ByteString
import play.api.mvc.AnyContentAsRaw
import play.api.test.FakeRequest
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Wraps a freshly generated Ed25519 keypair and produces signed FakeRequests. */
class RequestSigner:

  private val b64url = Base64.getUrlEncoder.withoutPadding

  private val gen = new Ed25519KeyPairGenerator()
  gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()))
  private val pair    = gen.generateKeyPair()
  private val privKey = pair.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters]
  private val pubKey  = pair.getPublic.asInstanceOf[Ed25519PublicKeyParameters]

  val publicKeyHeader: String = b64url.encodeToString(pubKey.getEncoded)

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  def authHeaders(method: String, path: String, body: Array[Byte] = Array.empty): Seq[(String, String)] =
    val nonce  = s"${System.currentTimeMillis()}.${UUID.randomUUID().toString.take(8)}"
    val canon  = s"$nonce\n${method.toUpperCase}\n$path\n${sha256Hex(body)}".getBytes("UTF-8")
    val signer = new Ed25519Signer()
    signer.init(true, privKey)
    signer.update(canon, 0, canon.length)
    Seq(
      "X-Deposplit-Public-Key" -> publicKeyHeader,
      "X-Deposplit-Nonce"      -> nonce,
      "X-Deposplit-Signature"  -> b64url.encodeToString(signer.generateSignature())
    )

  def get(path: String) =
    FakeRequest("GET", path).withHeaders(authHeaders("GET", path)*)

  def delete(path: String) =
    FakeRequest("DELETE", path).withHeaders(authHeaders("DELETE", path)*)

  def post(path: String, body: Array[Byte]): FakeRequest[AnyContentAsRaw] =
    FakeRequest("POST", path)
      .withHeaders((authHeaders("POST", path, body) :+ ("Content-Type" -> "application/json"))*)
      .withRawBody(ByteString(body))

  def patch(path: String, body: Array[Byte]): FakeRequest[AnyContentAsRaw] =
    FakeRequest("PATCH", path)
      .withHeaders((authHeaders("PATCH", path, body) :+ ("Content-Type" -> "application/json"))*)
      .withRawBody(ByteString(body))
