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

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Client-side mirror of `hexagons/relay`'s `PayloadCanonical` (this module can't depend on
  * `relay` — separate sbt subprojects with no dependency between them). Byte-for-byte identical
  * construction; keep both in sync. See the relay module's `PayloadCanonical` for the full
  * rationale (epoch-millis timestamps, lowercase UUIDs — both exist purely to keep this
  * byte-identical across the JVM, Kotlin, and Swift implementations).
  */
object PayloadCanonical:

  private val base64Std = Base64.getEncoder
  private val base64Url = Base64.getUrlEncoder.withoutPadding

  private def requestTypeWire(rt: ShareRequestType): String = rt match
    case ShareRequestType.PickUp           => "pick_up"
    case ShareRequestType.Retrieve         => "retrieve"
    case ShareRequestType.Delete           => "delete"
    case ShareRequestType.RecoveryMetadata => "recovery_metadata"

  /** Signed by the sender when opening a share request (`senderSignature`).
    *
    * `k`/`n` (item 8) are appended at the end of the sequence, keeping the existing field order
    * — and this construction's cross-platform byte-vector test — undisturbed.
    */
  def forOpen(
      secretId: UUID,
      requestType: ShareRequestType,
      recipientKey: Array[Byte],
      label: String,
      secretCreatedAt: Instant,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]],
      k: Option[Int] = None,
      n: Option[Int] = None
  ): Array[Byte] =
    Seq(
      secretId.toString,
      requestTypeWire(requestType),
      base64Url.encodeToString(recipientKey),
      label,
      secretCreatedAt.toEpochMilli.toString,
      shareId.fold("")(_.toString),
      ciphertext.fold("")(base64Std.encodeToString),
      k.fold("")(_.toString),
      n.fold("")(_.toString)
    ).mkString("\n").getBytes(StandardCharsets.UTF_8)

  /** Signed by the recipient when responding to a share request (`recipientSignature`). */
  def forRespond(requestId: UUID, approved: Boolean, ciphertext: Option[Array[Byte]]): Array[Byte] =
    Seq(
      requestId.toString,
      if approved then "approved" else "denied",
      ciphertext.fold("")(base64Std.encodeToString)
    ).mkString("\n").getBytes(StandardCharsets.UTF_8)
