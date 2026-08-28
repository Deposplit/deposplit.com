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

enum Role:
  case Sender, Recipient

/** The kind of thing that happened (or is being asked to happen) to a share, phrased as a neutral transaction noun
  * rather than either party's verb — see deposplit.com/CLAUDE.md "Cross-cutting implementation chores" for why: naming
  * from a single named actor's point of view (Alice's, or Bob's) breaks down because the actor genuinely alternates —
  * Alice always opens Deposit/Retrieval/Removal, but the *holder* opens Inventory (holder → owner).
  *
  * `wireValue` is the single source of truth for this type's wire representation, mirroring the relay's
  * `ShareTransactionType` (this module can't depend on `relay` — separate sbt subprojects with no dependency between
  * them).
  */
enum ShareTransactionType(val wireValue: String):
  case Deposit extends ShareTransactionType("deposit")
  case Retrieval extends ShareTransactionType("retrieval")
  case Removal extends ShareTransactionType("removal")
  case Inventory extends ShareTransactionType("inventory")

object ShareTransactionType:
  def fromWire(s: String): Option[ShareTransactionType] = values.find(_.wireValue == s)

enum ShareRequestState:
  /** Deposit-only (item 9): the recipient unilaterally stopped holding the share. A best-effort tombstone, not
    * authoritative — see `ShareRelay.withdrawShareRequests`.
    */
  case Pending, Approved, Denied, Withdrawn

/** Flat mirror of the relay's ShareRequest — every request type uses the same structure.
  *
  * `senderSignature`/`recipientSignature` are Ed25519 signatures over `PayloadCanonical`'s byte constructions,
  * independent of the transport-auth signature — see `PayloadCanonical` for why.
  */
case class ShareRequest(
    id: UUID,
    secretId: UUID,
    senderKey: Array[Byte],
    recipientKey: Array[Byte],
    label: String,
    secretCreatedAt: Instant,
    transactionType: ShareTransactionType,
    state: ShareRequestState,
    /** For Retrieval/Removal rows: the originating Deposit request's id. */
    shareId: Option[UUID],
    requestedAt: Instant,
    respondedAt: Option[Instant],
    ciphertext: Option[Array[Byte]],
    // SSS threshold/share-count — populated for Deposit/Inventory, None for
    // Retrieval/Removal. See deposplit.com/CLAUDE.md "What is next" items 8 and 11.
    k: Option[Int] = None,
    n: Option[Int] = None,
    senderSignature: Array[Byte],
    recipientSignature: Option[Array[Byte]]
):
  override def equals(other: Any): Boolean = other match
    case r: ShareRequest => id == r.id
    case _               => false
  override def hashCode(): Int = id.hashCode()

/** Lightweight record Alice stores locally when she deposits a share (one per Deposit request). Normalized to reference
  * its parent `Secret` (by `secretId`) rather than duplicating `label`/`secretCreatedAt` — see deposplit.com/CLAUDE.md
  * "What is next" item 11.
  */
case class ShareMetadata(
    id: UUID, // Deposit request ID — used as shareId in Retrieval/Removal requests
    secretId: UUID,
    // The holder's stable local contact id — not their Ed25519 key — so this record survives a
    // holder key rotation/recovery (see deposplit.com/CLAUDE.md "What is next" item 7).
    contactId: UUID,
    // Item 12 — the last time this holder proved custody (relay-observed pickup, a retrieve
    // approval, or a custodial heartbeat), gating whether this share counts toward n_live within
    // CustodyHeartbeatTuning.lossThreshold. None until first confirmed.
    lastConfirmedAt: Option[Instant] = None
) extends Serializable
