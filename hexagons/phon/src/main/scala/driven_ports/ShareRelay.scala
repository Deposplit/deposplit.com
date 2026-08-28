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

import value_objects.svo.CipherSuite
import value_objects.svo.CustodyHeartbeat
import value_objects.svo.KeyRotation
import value_objects.svo.Role
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareTransactionType

import java.time.Instant
import java.util.UUID

trait ShareRelay:
  /** Open a Deposit, Retrieval, or Removal request on the relay. For Deposit: ciphertext must be supplied (the
    * encrypted share). For Retrieval/Removal: shareId should carry the originating Deposit's id; ciphertext is absent.
    */
  def openShareRequest(
      secretId: UUID,
      recipientKey: Array[Byte],
      label: String,
      secretCreatedAt: Instant,
      transactionType: ShareTransactionType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]],
      k: Option[Int] = None,
      n: Option[Int] = None,
      senderSignature: Array[Byte]
  ): ShareRequest

  def listShareRequests(
      role: Role,
      transactionType: Option[ShareTransactionType] = None,
      state: Option[ShareRequestState] = None
  ): List[ShareRequest]

  def getShareRequest(requestId: UUID): ShareRequest

  def respondToShareRequest(
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]] = None,
      recipientSignature: Array[Byte]
  ): ShareRequest

  /** Delete a single request by id. When the caller deletes a Deposit, the relay cascades to Retrieval/Removal rows for
    * the same share.
    */
  def deleteShareRequest(requestId: UUID): Unit

  /** Recipient-initiated bulk delete — removes all requests where the caller is the recipient, optionally filtered by
    * sender key and/or secret id.
    */
  def deleteShareRequests(senderKey: Option[Array[Byte]], secretId: Option[UUID]): Unit

  /** Recipient-initiated unilateral withdrawal (item 9) — flips matching approved Deposit rows to Withdrawn on the
    * relay instead of deleting them, so the sender's next poll can observe the tombstone. Best-effort and
    * fire-and-forget.
    */
  def withdrawShareRequests(senderKey: Option[Array[Byte]] = None, secretId: Option[UUID] = None): Unit

  // Item 9's signed rotate(K_old -> K_new) push. Grouped onto this trait rather than a separate
  // port: it's the same physical relay endpoint and the same BYOR per-contact routing as every
  // other ShareRelay call. deposplit.com's own backend keeps rotation pushes in a dedicated
  // key_rotations table/KeyRotations service for domain-purity reasons (no secretId, no consent
  // phase) that are about server-side schema shape, not about this client-side HTTP-calling
  // port, so no equivalent split is needed here.

  /** Pushes a signed rotation notice to one contact. `signature` must verify against the caller's own current verify
    * key (the relay's `oldVerifyKey`) over `value_objects.svo.PayloadCanonical.forRotation`. `newCipherSuite` (item 14)
    * is the signing + key-agreement algorithm pairing `newVerifyKey`/`newEncKey` use.
    */
  def pushRotation(
      recipientKey: Array[Byte],
      newVerifyKey: Array[Byte],
      newEncKey: Array[Byte],
      newCipherSuite: CipherSuite,
      signature: Array[Byte]
  ): Unit

  /** Rotation notices addressed to this device. */
  def listRotations(): List[KeyRotation]

  /** Deletes a rotation notice once consumed. */
  def deleteRotation(id: UUID): Unit

  // Item 12's signed custodial-heartbeat push. Grouped onto this trait for the same reason as
  // the rotation methods above — same physical relay endpoint, same BYOR per-contact routing.

  /** Pushes (upserts) a signed heartbeat to one owner. `signature` must verify against the caller's own current Ed25519
    * key (the relay's `holderKey`) over `value_objects.svo.PayloadCanonical.forHeartbeat`. The same call covers the
    * opt-out notice (`optedOut = true`).
    */
  def pushHeartbeat(ownerKey: Array[Byte], secretIds: Seq[UUID], optedOut: Boolean, signature: Array[Byte]): Unit

  /** The latest heartbeat from each holder addressed to this device (the owner). Never consumed-and-deleted — see
    * `CustodyHeartbeat` for why it's a standing status, not a one-shot delivery.
    */
  def listHeartbeats(): List[CustodyHeartbeat]
