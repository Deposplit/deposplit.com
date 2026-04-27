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

trait Shares:

  // --- Sender operations ---

  def depositShare(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: SecretId,
      label: Label,
      createdAt: Instant,
      ciphertext: Array[Byte]
  ): Either[Error, ShareMetadata]

  /** Lists shares.
    *
    * `asSender = true`  — all shares deposited by `callerKey`, including picked-up ones.
    * `asSender = false` — shares in `callerKey`'s inbox (not yet picked up).
    */
  def listShares(
      callerKey: PublicKey,
      asSender: Boolean,
      counterpartyKey: Option[PublicKey]
  ): Either[Error, Seq[ShareMetadata]]

  /** Opens a retrieve or delete consent request for the share identified by `shareId`.
    * The caller must be the share's sender. Returns `Forbidden` otherwise.
    * Returns `Conflict` if a pending request of the same type already exists.
    */
  def openShareRequest(
      senderKey: PublicKey,
      shareId: UUID,
      requestType: ShareRequestType
  ): Either[Error, ShareRequest]

  // --- Recipient operations ---

  /** Picks up a deposited share: returns the ciphertext and clears it from the relay.
    * The ciphertext now lives only on the recipient's device.
    * Returns `Conflict` if the share has already been picked up.
    */
  def pickUpShare(
      recipientKey: PublicKey,
      shareId: UUID
  ): Either[Error, Array[Byte]]

  /** Lists share requests.
    *
    * `asSender = true`  — requests opened by `callerKey` (sender polling for resolution).
    * `asSender = false` — requests directed at `callerKey` (recipient deciding).
    */
  def listShareRequests(
      callerKey: PublicKey,
      asSender: Boolean,
      state: Option[ShareRequestState]
  ): Either[Error, Seq[ShareRequest]]

  /** Returns the request if the caller is either the sender or the recipient.
    * Returns `Forbidden` otherwise. For approved retrieve requests the `ciphertext` field
    * carries the bytes the recipient deposited when approving.
    */
  def getShareRequest(
      callerKey: PublicKey,
      requestId: UUID
  ): Either[Error, ShareRequest]

  /** Approves or denies a pending consent request. The caller must be the recipient.
    * Approving a delete request deletes the share row immediately.
    * Approving a retrieve request requires the recipient to supply the ciphertext from local
    * storage; `BadRequest` is returned if it is absent.
    */
  def respondToShareRequest(
      recipientKey: PublicKey,
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]]
  ): Either[Error, ShareRequest]

  /** Deletes a share row from the relay.
    * - Recipient: always allowed (unilateral inbox management).
    * - Sender: allowed only after the share has been picked up (`Conflict` otherwise).
    *   Use Message 4 (delete consent request) to ask the recipient to delete their local copy.
    * Returns `Forbidden` for any other caller.
    */
  def deleteShareById(
      callerKey: PublicKey,
      shareId: UUID
  ): Either[Error, Unit]

  /** Bulk recipient-initiated deletion — unilateral, no sender consent required.
    * `senderKey = None` deletes all of the recipient's shares regardless of sender.
    * `secretId  = None` deletes all shares from the given sender.
    */
  def deleteShares(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit]
