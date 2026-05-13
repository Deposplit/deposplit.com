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
import java.time.Instant
import java.util.UUID

trait ShareRepository:

  // --- Shares ---

  def saveShare(share: Share): Unit

  def getShareById(id: UUID): Option[Share]

  def getShare(secretId: SecretId, senderKey: PublicKey, recipientKey: PublicKey): Option[Share]

  /** Shares deposited by `senderKey`, optionally filtered to a specific recipient.
    * Returns all rows regardless of pickup status so the sender can track delivery.
    */
  def getSharesAsSender(senderKey: PublicKey, counterpartyKey: Option[PublicKey]): Seq[ShareMetadata]

  /** Shares in `recipientKey`'s inbox (not yet picked up), optionally filtered to a specific sender. */
  def getSharesAsRecipient(recipientKey: PublicKey, counterpartyKey: Option[PublicKey]): Seq[ShareMetadata]

  /** Clears the ciphertext from a share row and records the pickup timestamp.
    * The service reads the ciphertext from the share before calling this.
    */
  def pickUpShare(shareId: UUID): Unit

  /** Deletes a share row by primary key. Auth checks are the caller's responsibility. */
  def deleteShareByPK(shareId: UUID): Unit

  /** Bulk recipient-initiated deletion — filtered by `recipientKey` plus optional `senderKey`/`secretId`. */
  def deleteShares(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Unit

  // --- Share requests ---

  def saveShareRequest(request: ShareRequest): Unit

  def getShareRequestById(requestId: UUID): Option[ShareRequest]

  /** Requests opened by `senderKey`, optionally filtered by state. */
  def getShareRequestsAsSender(senderKey: PublicKey, state: Option[ShareRequestState]): Seq[ShareRequest]

  /** Requests directed at `recipientKey`, optionally filtered by state. */
  def getShareRequestsAsRecipient(recipientKey: PublicKey, state: Option[ShareRequestState]): Seq[ShareRequest]

  /** True if a Pending request of the given type already exists for this share. */
  def hasPendingRequest(shareId: UUID, requestType: ShareRequestType): Boolean

  /** Updates the state and response timestamp of a request.
    * `ciphertext` is stored for approved retrieve requests (provided by the recipient's app).
    */
  def updateShareRequest(requestId: UUID, state: ShareRequestState, respondedAt: Instant, ciphertext: Option[Array[Byte]]): Unit
