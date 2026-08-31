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

import java.time.Duration

/** The custodial-heartbeat cadence/staleness numbers — UI tuning, not load-bearing spec: the load-bearing part is not
  * the interval number but the two guarantees it makes. Shared between `ShareService` (emission cadence, on the holder
  * side) and any future owner-side health/freshness display, so both halves agree on the same numbers.
  */
object CustodyHeartbeatTuning:

  /** How often a holder re-emits a heartbeat to a given sender, opportunistically piggybacked on the existing inbox
    * poll — not a background timer.
    */
  val emissionInterval: Duration = Duration.ofDays(3)

  /** A holder confirmed within this window still counts toward n_live. Set well above `emissionInterval` so a single
    * missed beat is never mistaken for loss (guarantee (b)).
    */
  val lossThreshold: Duration = emissionInterval.multipliedBy(3)

  /** Below `lossThreshold` but past this point, the UI nudges "getting stale" — an early warning surfaced before a
    * holder actually drops out of n_live.
    */
  val staleWarningThreshold: Duration = emissionInterval.multipliedBy(2)
