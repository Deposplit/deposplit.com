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

/** Four-level ordinal verification model — see deposplit.com/CLAUDE.md "What is next" item 6.
  * Derived from a trusted-channel x proof-of-life lattice; the two incomparable middle cells are
  * merged into Low, so the order is simply the count of independent assurances present (0/1/2),
  * or 3 for physical co-presence. `compare` is ordinal-based — do not reorder the cases.
  */
enum VerificationLevel extends Ordered[VerificationLevel]:
  case VeryLow, Low, High, VeryHigh
  def compare(that: VerificationLevel): Int = this.ordinal - that.ordinal

case class Contact(
    id: UUID,
    pseudonym: String,
    verifyKey: Array[Byte],
    encKey: Array[Byte],
    verificationLevel: VerificationLevel,
    verifiedAt: Option[Instant],
    addedAt: Instant,
    /** BYOR override — None means "use the device's configured default relay". A pinned snapshot
      * at contact-add time, not a live pointer, same TOFU trust model as the public keys.
      */
    relayBaseUrl: Option[String] = None,
    /** Item 10 — historical Ed25519 keys locally flagged compromised via
      * `ContactManagement.markKeyCompromised`, out-of-band. A signed rotation notice claiming
      * continuity from any key in this set is refused auto-accept (see `ShareService`'s
      * rotation-processing) — revocation is socially anchored, so only a fresh human-verified
      * relink can move the contact forward once a key lands here. Never cleared automatically.
      */
    revokedEdKeys: List[Array[Byte]] = Nil,
    /** Item 10 — when edPublicKey (or xPublicKey) last changed via updateContact, whether through
      * a human-verified relink (item 8) or an auto-accepted rotation (item 9). None until the
      * first key change. Surfaced on the retrieve-approval screen as "this requester's key
      * changed N days ago" — the attack signature item 10 hardens against is key change followed
      * by a quick retrieval request.
      */
    keyChangedAt: Option[Instant] = None,
    /** Item 12, owner role — this contact (as a holder of one of my secrets) sent a signed
      * opt-out notice at this time: "my silence from here on is not a loss signal". None means
      * either never opted out, or opted back in (cleared on the next non-opted-out heartbeat).
      * Durable and local — captured the instant the notice is observed, since the relay may lose
      * its state at any time and must never be relied on to keep this alert alive.
      */
    heartbeatOptedOutAt: Option[Instant] = None,
    /** Item 12, holder role — when this device last pushed a custodial heartbeat *to* this
      * contact (who is the owner in that relationship). Drives ShareService's opportunistic
      * per-sender emission cadence; reset to None by setHeartbeatEmissionOptedOut so a toggled
      * preference reaches the contact on the very next poll rather than waiting out the interval.
      */
    lastHeartbeatSentAt: Option[Instant] = None,
    /** Item 12, holder role — this device's own choice to stop heartbeating this contact (who is
      * the owner in that relationship). Defaults to false (heartbeating is opt-out, not opt-in).
      */
    heartbeatEmissionOptedOut: Boolean = false,
    /** Item 14 — the signing + key-agreement algorithm pairing this contact currently uses.
      * Defaulted (not required) purely to keep the large item-14 rename from also being a
      * "thread a new value through every call site" exercise; the default is correct today (every
      * contact really is on this one suite), not a placeholder.
      */
    cipherSuite: CipherSuite = CipherSuite.current
) extends Serializable:
  override def equals(other: Any): Boolean = other match
    case c: Contact => id == c.id
    case _          => false
  override def hashCode(): Int = id.hashCode()
