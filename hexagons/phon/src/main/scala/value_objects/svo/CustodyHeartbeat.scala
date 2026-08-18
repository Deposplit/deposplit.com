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

/** Item 12's signed custodial-heartbeat push addressed to this device — a holder's proactive
  * "still guarding {secretIds} for you" notice (or, when `optedOut` is true, a signed "my
  * silence from here on is not a loss signal" notice). Deliberately not a `ShareRequest`: no
  * singular `secretId`, no consent phase — and unlike `KeyRotation`, never consumed-and-deleted:
  * the relay keeps only the latest heartbeat per (holder, owner) pair, so this is read
  * repeatedly, not drained. See `PayloadCanonical.forHeartbeat` for the exact bytes signed, and
  * deposplit.com/CLAUDE.md "What is next" item 12.
  */
case class CustodyHeartbeat(
    id: UUID,
    holderKey: Array[Byte],
    ownerKey: Array[Byte],
    secretIds: Seq[UUID],
    optedOut: Boolean,
    signature: Array[Byte],
    createdAt: Instant
):
  override def equals(other: Any): Boolean = other match
    case h: CustodyHeartbeat => id == h.id
    case _                   => false
  override def hashCode(): Int = id.hashCode()
