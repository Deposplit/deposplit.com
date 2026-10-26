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

/** A signed custodial-heartbeat push — a holder's proactive "still guarding {secretIds} for you" notice, addressed to
  * one owner at a time, or the same holder's signed opt-out notice ("my silence from here on is not a loss signal")
  * when `optedOut` is true.
  *
  * Deliberately not a `ShareRequest`: no `secretId` column, no consent phase. Like `KeyRotation` and Inventory it is a
  * message, consumed and deleted by its owner once read; the owner's freshness state lives on the owner's own device,
  * and the holder re-emits every interval, so nothing is lost with the row. Until it is read, a newer push from the
  * same holder to the same owner replaces it (upserted, never accumulated), under a fresh `id` each time, so an owner
  * deleting the one she read can never take a newer one with it.
  *
  * `emittedAt` is the holder's own clock at signing and is covered by the signature — it is the time the owner dates
  * custody from. `createdAt` is this relay's receipt time, unsigned, and for operators only: a relay that could set the
  * time custody was last proven could keep a lost holder looking fresh by re-serving an old heartbeat.
  */
case class CustodyHeartbeat(
    id: UUID,
    holderKey: PublicKey,
    ownerKey: PublicKey,
    secretIds: Seq[UUID],
    optedOut: Boolean,
    emittedAt: Instant,
    signature: Signature,
    createdAt: Instant
)
