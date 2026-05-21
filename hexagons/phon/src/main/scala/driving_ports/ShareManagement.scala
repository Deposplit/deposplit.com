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

import java.util.UUID
import value_objects.{Contact, HeldShare, ShareMetadata, ShareRequest, ShareRequestType}

trait ShareManagement:
  // ─── Sender ────────────────────────────────────────────────────────────────
  def deposit(secret: Array[Byte], label: String, contacts: List[Contact], threshold: Int): Unit
  def listDistributed(): List[ShareMetadata]
  def listSentRequests(): List[ShareRequest]
  def requestAll(secretId: UUID): Unit
  def openRequest(shareId: UUID, requestType: ShareRequestType): ShareRequest
  def reconstruct(secretId: UUID): Array[Byte]

  // ─── Recipient ──────────────────────────────────────────────────────────────
  def syncInbox(): Unit
  def listHeld(): List[HeldShare]
  def listPendingRequests(): List[ShareRequest]
  def respond(requestId: UUID, approved: Boolean): Unit
  def deleteHeldShare(shareId: UUID): Unit
  def deleteAllHeldFromSender(senderKey: Array[Byte]): Unit
