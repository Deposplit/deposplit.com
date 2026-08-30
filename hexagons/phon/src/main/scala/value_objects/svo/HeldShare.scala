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

case class HeldShare(
    id: UUID,
    secretId: UUID,
    label: String,
    // The sender's stable local contact id — not their Ed25519 key — so this record survives a
    // sender key rotation/recovery.
    contactId: UUID,
    createdAt: Instant,
    pickedUpAt: Instant,
    // The decrypted share, plaintext at rest: a single holder's share is
    // information-theoretically empty on its own, so this is safe to store unencrypted.
    plaintextShare: Array[Byte],
    // SSS threshold/share-count, carried on the deposit that produced this share — reported back
    // during identity recovery so a recovering owner can rebuild her Secret record.
    k: Int,
    n: Int
) extends Serializable:
  override def equals(other: Any): Boolean = other match
    case h: HeldShare => id == h.id
    case _            => false
  override def hashCode(): Int = id.hashCode()
