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

import java.time.Instant
import java.util.UUID

/** A signed key-rotation push (item 9) — a holder's proactive "I am now newEd25519Key, previously
  * oldEd25519Key" notice, addressed to one contact (`recipientKey`) at a time.
  *
  * Deliberately not a `ShareRequest`: it carries no `secretId` and has no consent phase — the
  * recipient auto-verifies `signature` against `oldEd25519Key` (the trusted key it already knows
  * this contact by) and, on success, updates its local contact record in place before deleting
  * this row. See `PayloadCanonical.forRotation` for the exact bytes signed.
  */
case class KeyRotation(
    id: UUID,
    oldEd25519Key: PublicKey,
    recipientKey: PublicKey,
    newEd25519Key: PublicKey,
    newX25519Key: X25519Key,
    signature: Signature,
    createdAt: Instant
)
