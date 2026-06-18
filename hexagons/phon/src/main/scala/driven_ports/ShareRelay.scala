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

package driven_ports

import value_objects.svo.Role
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareRequestType

import java.time.Instant
import java.util.UUID

trait ShareRelay:
  /** Open a PickUp, Retrieve, or Delete request on the relay.
    * For PickUp: ciphertext must be supplied (the encrypted share).
    * For Retrieve/Delete: shareId should carry the originating PickUp's id; ciphertext is absent.
    */
  def openShareRequest(
      secretId: UUID,
      recipientKey: Array[Byte],
      label: String,
      secretCreatedAt: Instant,
      requestType: ShareRequestType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]]
  ): ShareRequest

  def listShareRequests(
      role: Role,
      requestType: Option[ShareRequestType] = None,
      state: Option[ShareRequestState] = None
  ): List[ShareRequest]

  def getShareRequest(requestId: UUID): ShareRequest

  def respondToShareRequest(
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]] = None
  ): ShareRequest

  /** Delete a single request by id.
    * When the caller deletes a PickUp, the relay cascades to Retrieve/Delete rows for the same share.
    */
  def deleteShareRequest(requestId: UUID): Unit

  /** Recipient-initiated bulk delete — removes all requests where the caller is the recipient,
    * optionally filtered by sender key and/or secret id.
    */
  def deleteShareRequests(senderKey: Option[Array[Byte]], secretId: Option[UUID]): Unit
