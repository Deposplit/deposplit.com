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
  * now newVerifyKey, previously oldVerifyKey" notice. Deliberately not a `ShareRequest`: it
  * carries no `secretId` and has no consent phase — the recipient auto-verifies `signature`
  * against `oldVerifyKey` (the trusted key it already knows this contact by) and, on success,
  * updates its local contact record in place before deleting this notice. See
  * `PayloadCanonical.forRotation` for the exact bytes signed, and deposplit.com/CLAUDE.md "What
  * is next" item 9.
  *
  * `newCipherSuite` (item 14) is the signing + key-agreement algorithm pairing `newVerifyKey`/
  * `newEncKey` use. No `oldCipherSuite` field — the recipient already has it pinned on the
  * existing contact record being rotated away from.
  *
  * Keys are raw `Array[Byte]`, matching this hexagon's existing convention (`Contact`,
  * `ShareRequest`) rather than the relay's dedicated `PublicKey`/`X25519Key` opaque types — those
  * exist for server-side verification-path safety, which doesn't apply to this client-side mirror.
  */
case class KeyRotation(
    id: UUID,
    oldVerifyKey: Array[Byte],
    recipientKey: Array[Byte],
    newVerifyKey: Array[Byte],
    newEncKey: Array[Byte],
    newCipherSuite: CipherSuite,
    signature: Array[Byte],
    createdAt: Instant
):
  override def equals(other: Any): Boolean = other match
    case r: KeyRotation => id == r.id
    case _              => false
  override def hashCode(): Int = id.hashCode()
