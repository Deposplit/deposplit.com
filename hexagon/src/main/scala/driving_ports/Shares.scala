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
  ): Either[Error, Unit]

  def listShares(
      senderKey: PublicKey,
      recipientKey: PublicKey
  ): Either[Error, Seq[ShareMetadata]]

  def requestRetrieval(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: SecretId
  ): Either[Error, UUID]

  /** Requests consent to delete shares. `secretId = None` means delete all shares the sender has deposited with the
    * recipient.
    */
  def requestDeletion(
      senderKey: PublicKey,
      recipientKey: PublicKey,
      secretId: Option[SecretId]
  ): Either[Error, UUID]

  // --- Recipient operations ---

  def listPendingRequests(recipientKey: PublicKey): Either[Error, Seq[ShareRequest]]

  /** Approves a retrieval request and returns the share ciphertext. */
  def approveRetrieval(
      recipientKey: PublicKey,
      requestId: UUID
  ): Either[Error, Array[Byte]]

  /** Approves a deletion request and deletes the targeted share(s). */
  def approveDeletion(
      recipientKey: PublicKey,
      requestId: UUID
  ): Either[Error, Unit]

  def denyRequest(
      recipientKey: PublicKey,
      requestId: UUID
  ): Either[Error, Unit]

  /** Recipient-initiated deletion — unilateral, no consent required. */
  def deleteShares(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Either[Error, Unit]
