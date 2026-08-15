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

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Canonical byte constructions for the two payload-level signatures that ride with a
  * ShareRequest row (`senderSignature`, `recipientSignature`), independent of the per-call
  * transport-auth signature verified by `AuthHelper`.
  *
  * The transport signature authenticates the HTTP caller for one specific call and is never
  * persisted — it gives a later reader of a row nothing to re-verify authorship against. These
  * two signatures ride with the row instead, so any reader (not just the relay that received the
  * original request) can independently re-verify who authored it, using only the author's
  * Ed25519 public key. This is required for BYOR: a passive third-party relay performs no
  * verification of its own.
  *
  * Same newline-joined UTF-8 idiom as the transport canonical string. Two deliberate choices
  * exist purely to keep this byte-identical across three independently-implemented platforms
  * (JVM, Kotlin, Swift):
  *   - `secretCreatedAt` is encoded as epoch milliseconds, not an ISO-8601 string, to avoid
  *     formatting drift between java.time and Swift's Date/ISO8601DateFormatter.
  *   - UUIDs are rendered lowercase (Java/Kotlin's `UUID.toString` default) — Swift's
  *     `UUID.uuidString` is uppercase by default and MUST be lowercased by the iOS
  *     implementation to match.
  */
object PayloadCanonical:

  private val base64Std = Base64.getEncoder

  private def requestTypeWire(rt: ShareRequestType): String = rt match
    case ShareRequestType.PickUp           => "pick_up"
    case ShareRequestType.Retrieve         => "retrieve"
    case ShareRequestType.Delete           => "delete"
    case ShareRequestType.RecoveryMetadata => "recovery_metadata"

  /** Signed by the sender when opening a share request (`senderSignature`).
    *
    * `k`/`n` were added by item 8 (identity recovery) — populated for PickUp and
    * RecoveryMetadata, `None` for Retrieve/Delete; appended at the end of the sequence to
    * keep the existing field order (and its cross-platform byte-vector test) undisturbed.
    */
  def forOpen(
      secretId: SecretId,
      requestType: ShareRequestType,
      recipientKey: PublicKey,
      label: Label,
      secretCreatedAt: Instant,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]],
      k: Option[Int] = None,
      n: Option[Int] = None
  ): Array[Byte] =
    Seq(
      secretId.value.toString,
      requestTypeWire(requestType),
      recipientKey.toBase64Url,
      label.value,
      secretCreatedAt.toEpochMilli.toString,
      shareId.fold("")(_.toString),
      ciphertext.fold("")(base64Std.encodeToString),
      k.fold("")(_.toString),
      n.fold("")(_.toString)
    ).mkString("\n").getBytes(StandardCharsets.UTF_8)

  /** Signed by the recipient when responding to a share request (`recipientSignature`).
    * Required on every response, including denials — a forged denial is as much a
    * consent-authenticity violation as a forged approval.
    */
  def forRespond(requestId: UUID, approved: Boolean, ciphertext: Option[Array[Byte]]): Array[Byte] =
    Seq(
      requestId.toString,
      if approved then "approved" else "denied",
      ciphertext.fold("")(base64Std.encodeToString)
    ).mkString("\n").getBytes(StandardCharsets.UTF_8)
