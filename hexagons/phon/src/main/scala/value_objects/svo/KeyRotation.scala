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

/** A signed key-rotation push addressed to this device (item 9) — a contact's proactive "I am
  * now newEd25519Key, previously oldEd25519Key" notice. Deliberately not a `ShareRequest`: it
  * carries no `secretId` and has no consent phase — the recipient auto-verifies `signature`
  * against `oldEd25519Key` (the trusted key it already knows this contact by) and, on success,
  * updates its local contact record in place before deleting this notice. See
  * `PayloadCanonical.forRotation` for the exact bytes signed, and deposplit.com/CLAUDE.md "What
  * is next" item 9.
  *
  * Keys are raw `Array[Byte]`, matching this hexagon's existing convention (`Contact`,
  * `ShareRequest`) rather than the relay's dedicated `PublicKey`/`X25519Key` opaque types — those
  * exist for server-side verification-path safety, which doesn't apply to this client-side mirror.
  */
case class KeyRotation(
    id: UUID,
    oldEd25519Key: Array[Byte],
    recipientKey: Array[Byte],
    newEd25519Key: Array[Byte],
    newX25519Key: Array[Byte],
    signature: Array[Byte],
    createdAt: Instant
):
  override def equals(other: Any): Boolean = other match
    case r: KeyRotation => id == r.id
    case _              => false
  override def hashCode(): Int = id.hashCode()
