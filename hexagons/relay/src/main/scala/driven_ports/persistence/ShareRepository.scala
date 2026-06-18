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

  def saveShareRequest(request: ShareRequest): Unit

  def getShareRequestById(id: UUID): Option[ShareRequest]

  /** Requests opened by `senderKey`, optionally filtered by type and/or state. */
  def getShareRequestsAsSender(
      senderKey: PublicKey,
      requestType: Option[ShareRequestType],
      state: Option[ShareRequestState]
  ): Seq[ShareRequest]

  /** Requests directed at `recipientKey`, optionally filtered by type and/or state. */
  def getShareRequestsAsRecipient(
      recipientKey: PublicKey,
      requestType: Option[ShareRequestType],
      state: Option[ShareRequestState]
  ): Seq[ShareRequest]

  /** True if a non-denied PickUp already exists for this (secretId, recipientKey) pair.
    * Used to prevent duplicate deposits (a denied PickUp allows re-deposit).
    */
  def hasActivePickUp(secretId: SecretId, recipientKey: PublicKey): Boolean

  /** True if a Pending request of the given type already exists for this
    * (secretId, senderKey, recipientKey) triple. Used for Retrieve and Delete.
    */
  def hasPendingRequest(
      secretId: SecretId,
      senderKey: PublicKey,
      recipientKey: PublicKey,
      requestType: ShareRequestType
  ): Boolean

  /** Updates the state, response timestamp, and ciphertext of a request. */
  def updateShareRequest(
      requestId: UUID,
      state: ShareRequestState,
      respondedAt: Instant,
      ciphertext: Option[Array[Byte]]
  ): Unit

  /** Deletes a single request row by primary key. */
  def deleteShareRequestById(id: UUID): Unit

  /** Bulk delete — all rows where `recipientKey` matches, optionally filtered
    * by `senderKey` and/or `secretId`. Used for recipient-initiated cleanup and
    * cascaded deletion when a PickUp row is removed.
    */
  def deleteShareRequests(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Unit
