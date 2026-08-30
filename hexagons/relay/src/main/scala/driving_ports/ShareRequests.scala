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

import java.time.Instant
import java.util.UUID

trait ShareRequests:

  /** Opens a share request of any type.
    *
    *   - Deposit: Alice deposits a share for Bob. `ciphertext` is required (`BadRequest` if absent). `k`/`n` are
    *     required and must satisfy `2 <= k <= n <= 255` (`BadRequest` otherwise), the same hard bound `split()` and
    *     `combine()` enforce client-side. Returns `Conflict` if a non-denied Deposit for (secretId, recipientKey)
    *     already exists.
    *   - Retrieval: Alice asks Bob to return a share. `ciphertext`, `k`, and `n` must be None. Returns `Conflict` if a
    *     pending Retrieval for (secretId, senderKey, recipientKey) exists.
    *   - Removal: Alice asks Bob to delete his local copy. `ciphertext`, `k`, and `n` must be None. Returns `Conflict`
    *     if a pending Removal for (secretId, senderKey, recipientKey) exists.
    *   - Inventory: A holder (Bob) pushes a metadata-only report about a share of his back to its owner (Alice), during
    *     identity recovery. `ciphertext` must be None; `k`/`n` are required (same bounds as Deposit). Unlike the other
    *     three types this is **not consent-gated** — it's a fire-and-forget push, so the row is created directly in
    *     `Approved` state with no conflict check; the recipient (Alice) polls for it and deletes it once consumed.
    *
    * `shareId` is ignored for Deposit and Inventory. For Retrieval and Removal it should be the id of the originating
    * Deposit request — the relay stores it opaquely for the client's benefit.
    *
    * `senderSignature` must verify against `senderKey` over `PayloadCanonical.forOpen` of the other arguments —
    * defense-in-depth alongside the transport-auth signature already checked by the caller (`AuthHelper`). Returns
    * `BadRequest` if it doesn't verify.
    */
  def openShareRequest(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: SecretId,
      label: Label,
      secretCreatedAt: Instant,
      transactionType: ShareTransactionType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]],
      k: Option[Int],
      n: Option[Int],
      senderSignature: Signature
  ): Either[Error, ShareRequest]

  /** Lists share requests, optionally filtered by type and/or state.
    *
    * `asSender = true` — requests opened by `callerKey`. `asSender = false` — requests directed at `callerKey` (inbox).
    */
  def listShareRequests(
      callerKey: PublicKey,
      asSender: Boolean,
      transactionType: Option[ShareTransactionType],
      state: Option[ShareRequestState]
  ): Either[Error, Seq[ShareRequest]]

  /** Returns the request if the caller is either the sender or the recipient. */
  def getShareRequest(callerKey: PublicKey, requestId: UUID): Either[Error, ShareRequest]

  /** Approves or denies a pending request. The caller must be the recipient.
    *
    *   - Approving Deposit: relay delivers ciphertext to Bob (embedded in response) and clears it.
    *   - Approving Retrieval: Bob must supply `ciphertext`; relay stores it for Alice to collect. Returns `BadRequest`
    *     if ciphertext is absent.
    *   - Approving Removal: relay bulk-deletes all rows for (secretId, senderKey, recipientKey).
    *   - Denying any type: state → Denied; ciphertext cleared if present.
    *
    * Returns `Conflict` if the request is no longer Pending.
    *
    * `recipientSignature` must verify against `recipientKey` over `PayloadCanonical.forRespond` of the other arguments
    * — required unconditionally, including denials. Returns `BadRequest` if it doesn't verify.
    */
  def respondToShareRequest(
      recipientKey: PublicKey,
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]],
      recipientSignature: Signature
  ): Either[Error, ShareRequest]

  /** Deletes a request from the relay. Sender or recipient may delete any request they are party to. Deleting a Deposit
    * cascades to all Retrieval/Removal rows for the same (secretId, senderKey, recipientKey).
    */
  def deleteShareRequestById(callerKey: PublicKey, requestId: UUID): Either[Error, Unit]

  /** Bulk recipient-initiated deletion — unilateral, no sender consent required. Deletes all rows where `recipientKey`
    * is the recipient, optionally filtered by sender and/or secretId.
    */
  def deleteShareRequests(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit]

  /** Recipient-initiated unilateral withdrawal — Bob stops holding secretId (or all secrets from a given sender).
    * Unlike `deleteShareRequests`, this does not hard-delete the matching Deposit rows; it flips matching `Approved`
    * Deposit rows to `Withdrawn` so the sender's next poll can observe the tombstone. Retrieval/Removal rows for the
    * same grouping are untouched — those are separate, already-resolving consent flows.
    *
    * Best-effort and fire-and-forget: the relay may still garbage-collect a `Withdrawn` row at any time, so its absence
    * must never be read as a signal — only an *observed* `Withdrawn` row counts.
    */
  def withdrawShareRequests(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit]
