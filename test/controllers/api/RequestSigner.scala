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

package controllers.api

import org.apache.pekko.util.ByteString
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
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
  private val pair = gen.generateKeyPair()
  private val privKey = pair.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters]
  private val pubKey = pair.getPublic.asInstanceOf[Ed25519PublicKeyParameters]

  val publicKeyHeader: String = b64url.encodeToString(pubKey.getEncoded)

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  def authHeaders(method: String, path: String, body: Array[Byte] = Array.empty): Seq[(String, String)] =
    val nonce = s"${System.currentTimeMillis()}.${UUID.randomUUID().toString.take(8)}"
    val canon = s"$nonce\n${method.toUpperCase}\n$path\n${sha256Hex(body)}".getBytes("UTF-8")
    Seq(
      "X-Deposplit-Public-Key" -> publicKeyHeader,
      "X-Deposplit-Nonce" -> nonce,
      "X-Deposplit-Signature" -> b64url.encodeToString(rawSign(canon))
    )

  private def rawSign(bytes: Array[Byte]): Array[Byte] =
    val signer = new Ed25519Signer()
    signer.init(true, privKey)
    signer.update(bytes, 0, bytes.length)
    signer.generateSignature()

  // ── Payload-level signatures (senderSignature / recipientSignature) ────────
  // Mirrors hexagons/relay's PayloadCanonical byte-for-byte — see that object for the
  // rationale (epoch-millis timestamps, lowercase UUIDs) behind this exact construction.

  /** Signs an `openShareRequest` payload. `secretCreatedAt` is the ISO-8601 wire string (e.g.
    * "2026-01-01T00:00:00Z"); `ciphertext` is the standard-base64 string as it appears on the
    * wire. `k`/`n` (item 8) are appended at the end of the sequence, mirroring PayloadCanonical.
    */
  def signOpen(
      secretId: String,
      transactionType: String,
      recipientKey: String,
      label: String,
      secretCreatedAt: String,
      shareId: Option[String] = None,
      ciphertext: Option[String] = None,
      k: Option[Int] = None,
      n: Option[Int] = None
  ): String =
    val epochMs = java.time.Instant.parse(secretCreatedAt).toEpochMilli
    val canon = Seq(
      secretId,
      transactionType,
      recipientKey,
      label,
      epochMs.toString,
      shareId.getOrElse(""),
      ciphertext.getOrElse(""),
      k.fold("")(_.toString),
      n.fold("")(_.toString)
    )
      .mkString("\n")
      .getBytes("UTF-8")
    b64url.encodeToString(rawSign(canon))

  /** Signs a `respondToShareRequest` payload. `ciphertext` is the standard-base64 string as it
    * appears on the wire.
    */
  def signRespond(requestId: String, approved: Boolean, ciphertext: Option[String] = None): String =
    val canon = Seq(requestId, if approved then "approved" else "denied", ciphertext.getOrElse(""))
      .mkString("\n")
      .getBytes("UTF-8")
    b64url.encodeToString(rawSign(canon))

  /** Signs a `pushRotation` payload (item 9) — mirrors `PayloadCanonical.forRotation`. All three
    * arguments are base64url public key strings as they appear on the wire.
    */
  def signRotation(recipientKey: String, newEd25519Key: String, newX25519Key: String): String =
    val canon = Seq(recipientKey, newEd25519Key, newX25519Key).mkString("\n").getBytes("UTF-8")
    b64url.encodeToString(rawSign(canon))

  /** Signs a `pushHeartbeat` payload (item 12) — mirrors `PayloadCanonical.forHeartbeat`.
    * `secretIds` is sorted before joining, same as the relay's own construction.
    */
  def signHeartbeat(ownerKey: String, secretIds: Seq[String], optedOut: Boolean): String =
    val canon = Seq(ownerKey, secretIds.sorted.mkString(","), optedOut.toString).mkString("\n").getBytes("UTF-8")
    b64url.encodeToString(rawSign(canon))

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
