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
import value_objects.svo.ShareMetadata
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareRequestType

import java.time.Instant
import java.util.UUID

trait ShareRelay:
  def depositShare(secretId: UUID, label: String, recipientKey: Array[Byte], createdAt: Instant, ciphertext: Array[Byte]): ShareMetadata
  def listShares(role: Role, counterpartyKey: Option[Array[Byte]] = None): List[ShareMetadata]
  def pickUpShare(shareId: UUID): Array[Byte]
  def deleteShare(shareId: UUID): Unit
  def openShareRequest(shareId: UUID, requestType: ShareRequestType): ShareRequest
  def listShareRequests(role: Role, state: Option[ShareRequestState] = None): List[ShareRequest]
  def getShareRequest(requestId: UUID): ShareRequest
  def respondToShareRequest(requestId: UUID, approved: Boolean, ciphertext: Option[Array[Byte]] = None): ShareRequest
