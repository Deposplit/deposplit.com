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
    * - PickUp:   Alice deposits a share for Bob. `ciphertext` is required (`BadRequest` if absent).
    *             Returns `Conflict` if a non-denied PickUp for (secretId, recipientKey) already exists.
    * - Retrieve: Alice asks Bob to return a share. `ciphertext` must be None.
    *             Returns `Conflict` if a pending Retrieve for (secretId, senderKey, recipientKey) exists.
    * - Delete:   Alice asks Bob to delete his local copy. `ciphertext` must be None.
    *             Returns `Conflict` if a pending Delete for (secretId, senderKey, recipientKey) exists.
    *
    * `shareId` is ignored for PickUp. For Retrieve and Delete it should be the id of the
    * originating PickUp request — the relay stores it opaquely for the client's benefit.
    */
  def openShareRequest(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: SecretId,
      label: Label,
      secretCreatedAt: Instant,
      requestType: ShareRequestType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]]
  ): Either[Error, ShareRequest]

  /** Lists share requests, optionally filtered by type and/or state.
    *
    * `asSender = true`  — requests opened by `callerKey`.
    * `asSender = false` — requests directed at `callerKey` (inbox).
    */
  def listShareRequests(
      callerKey: PublicKey,
      asSender: Boolean,
      requestType: Option[ShareRequestType],
      state: Option[ShareRequestState]
  ): Either[Error, Seq[ShareRequest]]

  /** Returns the request if the caller is either the sender or the recipient. */
  def getShareRequest(callerKey: PublicKey, requestId: UUID): Either[Error, ShareRequest]

  /** Approves or denies a pending request. The caller must be the recipient.
    *
    * - Approving PickUp:   relay delivers ciphertext to Bob (embedded in response) and clears it.
    * - Approving Retrieve: Bob must supply `ciphertext`; relay stores it for Alice to collect.
    *                       Returns `BadRequest` if ciphertext is absent.
    * - Approving Delete:   relay bulk-deletes all rows for (secretId, senderKey, recipientKey).
    * - Denying any type:   state → Denied; ciphertext cleared if present.
    *
    * Returns `Conflict` if the request is no longer Pending.
    */
  def respondToShareRequest(
      recipientKey: PublicKey,
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]]
  ): Either[Error, ShareRequest]

  /** Deletes a request from the relay. Sender or recipient may delete any request they are party to.
    * Deleting a PickUp cascades to all Retrieve/Delete rows for the same (secretId, senderKey, recipientKey).
    */
  def deleteShareRequestById(callerKey: PublicKey, requestId: UUID): Either[Error, Unit]

  /** Bulk recipient-initiated deletion — unilateral, no sender consent required.
    * Deletes all rows where `recipientKey` is the recipient, optionally filtered by sender and/or secretId.
    */
  def deleteShareRequests(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit]
