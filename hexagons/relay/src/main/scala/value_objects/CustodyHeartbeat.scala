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

/** Item 12's signed custodial-heartbeat push — a holder's proactive "still guarding {secretIds} for you" notice,
  * addressed to one owner at a time, or the same holder's signed opt-out notice ("my silence from here on is not a loss
  * signal") when `optedOut` is true.
  *
  * Deliberately not a `ShareRequest`: no `secretId` column, no consent phase. Deliberately *not* consumed-and-deleted
  * like `KeyRotation`/Inventory either — a heartbeat is a standing "last seen" record, not a one-shot delivery, so the
  * relay keeps only the *latest* one per `(holderKey, ownerKey)` pair (upserted, never accumulated). The owner's
  * durable freshness state lives on the owner's own device, refreshed each time it observes this row — the relay row
  * itself may be GC'd at any time without consequence, per deposplit.com/CLAUDE.md item 12's "correctness comes from
  * idempotent re-emission ... not from retention."
  */
case class CustodyHeartbeat(
    id: UUID,
    holderKey: PublicKey,
    ownerKey: PublicKey,
    secretIds: Seq[UUID],
    optedOut: Boolean,
    signature: Signature,
    createdAt: Instant
)
