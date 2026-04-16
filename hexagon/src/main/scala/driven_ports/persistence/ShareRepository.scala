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
import java.util.UUID

trait ShareRepository:

  // --- Shares ---

  def saveShare(share: Share): Unit

  def getShareMetadata(senderKey: PublicKey, recipientKey: PublicKey): Seq[ShareMetadata]

  def getShare(secretId: SecretId, senderKey: PublicKey, recipientKey: PublicKey): Option[Share]

  /** Deletes shares held by `recipientKey`, optionally filtered by `senderKey` and/or `secretId`. Passing neither
    * filter deletes all shares held by the recipient (use with care).
    */
  def deleteShares(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Unit

  // --- Share requests ---

  def saveShareRequest(request: ShareRequest): Unit

  /** Returns the request only if `recipientKey` matches, preventing cross-recipient access. */
  def getShareRequest(requestId: UUID, recipientKey: PublicKey): Option[ShareRequest]

  def getPendingShareRequests(recipientKey: PublicKey): Seq[ShareRequest]

  def updateShareRequestState(requestId: UUID, state: ShareRequestState): Unit
