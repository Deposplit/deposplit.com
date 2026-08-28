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

package driving_ports

import value_objects.*

/** Item 12's signed custodial-heartbeat push — a holder's proactive "still guarding {secretIds} for you" notice (or
  * signed opt-out), addressed to one owner at a time. Deliberately separate from `ShareRequests`/`KeyRotations`: it
  * carries no `secretId` singular (a heartbeat covers a whole *list* of secrets in one coalesced push) and has no
  * consent phase or per-row consumption — see `value_objects.CustodyHeartbeat` for why it is upserted, not deleted.
  */
trait CustodyHeartbeats:

  /** Pushes (upserts) a signed heartbeat for one owner, replacing any previous heartbeat from the same holder to the
    * same owner. The authenticated caller becomes `holderKey` — `signature` must verify against it over
    * `PayloadCanonical.forHeartbeat`. Returns `BadRequest` if it doesn't verify.
    */
  def pushHeartbeat(
      holderKey: PublicKey,
      ownerKey: PublicKey,
      secretIds: Seq[java.util.UUID],
      optedOut: Boolean,
      signature: Signature
  ): Either[Error, CustodyHeartbeat]

  /** The latest heartbeat (or opt-out) from each holder addressed to `ownerKey`. */
  def listHeartbeats(ownerKey: PublicKey): Either[Error, Seq[CustodyHeartbeat]]
