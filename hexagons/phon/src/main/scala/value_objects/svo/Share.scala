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

import java.time.Instant
import java.util.UUID

enum Role:
  case Sender, Recipient

enum ShareRequestType:
  case PickUp, Retrieve, Delete

enum ShareRequestState:
  case Pending, Approved, Denied

/** Flat mirror of the relay's ShareRequest — every request type uses the same structure. */
case class ShareRequest(
    id: UUID,
    secretId: UUID,
    senderKey: Array[Byte],
    recipientKey: Array[Byte],
    label: String,
    secretCreatedAt: Instant,
    requestType: ShareRequestType,
    state: ShareRequestState,
    /** For Retrieve/Delete rows: the originating PickUp request's id. */
    shareId: Option[UUID],
    requestedAt: Instant,
    respondedAt: Option[Instant],
    ciphertext: Option[Array[Byte]]
):
  override def equals(other: Any): Boolean = other match
    case r: ShareRequest => id == r.id
    case _               => false
  override def hashCode(): Int = id.hashCode()

/** Lightweight record Alice stores locally when she deposits a share (one per PickUp request). */
case class ShareMetadata(
    id: UUID,              // PickUp request ID — used as shareId in Retrieve/Delete requests
    secretId: UUID,
    label: String,
    recipientKey: Array[Byte],  // Ed25519 key; used to look up the X25519 key for decryption
    secretCreatedAt: Instant
) extends Serializable
