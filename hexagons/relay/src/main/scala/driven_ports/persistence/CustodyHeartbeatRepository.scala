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

package driven_ports.persistence

import value_objects.*

trait CustodyHeartbeatRepository:

  /** Upserts by `(holderKey, ownerKey)` — a fresh push from the same holder to the same owner replaces an unread one
    * rather than accumulating, and takes `heartbeat.id` with it, so a delete by the replaced row's id cannot reach the
    * replacement. Returns the row as actually persisted.
    */
  def upsertHeartbeat(heartbeat: CustodyHeartbeat): CustodyHeartbeat

  /** The unread heartbeat (or opt-out) from each holder addressed to `ownerKey`. */
  def getHeartbeatsForOwner(ownerKey: PublicKey): Seq[CustodyHeartbeat]

  def getHeartbeatById(id: java.util.UUID): Option[CustodyHeartbeat]

  /** Deletes a single heartbeat row by primary key. */
  def deleteHeartbeatById(id: java.util.UUID): Unit

  /** Deletes every heartbeat whose `createdAt` — the receipt of its latest push — lies before `cutoff`. Returns how
    * many rows it deleted.
    */
  def deleteHeartbeatsReceivedBefore(cutoff: java.time.Instant): Int
