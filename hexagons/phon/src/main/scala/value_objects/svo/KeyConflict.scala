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

/** Item 10 — captured the instant a rotation notice's `oldVerifyKey` is found in a contact's
  * `revokedVerifyKeys`. Durable and local: the relay may lose its state at any time, so this is saved
  * before the corresponding relay notice is deleted, never re-derived from the relay later. Never
  * auto-resolved — the only path forward is a fresh human-verified relink; this record only exists
  * to be surfaced and dismissed.
  */
case class KeyConflict(
    id: UUID,
    contactId: UUID,
    oldVerifyKey: Array[Byte],
    newVerifyKey: Array[Byte],
    newEncKey: Array[Byte],
    detectedAt: Instant
) extends Serializable:
  override def equals(other: Any): Boolean = other match
    case c: KeyConflict => id == c.id
    case _              => false
  override def hashCode(): Int = id.hashCode()
