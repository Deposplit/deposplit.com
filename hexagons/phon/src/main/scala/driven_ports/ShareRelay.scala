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
import value_objects.svo.MimeType
import value_objects.svo.Role
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareTransactionType

import java.time.Instant
import java.util.UUID

/** An answer with an error status is thrown as [[value_objects.svo.RelayRefusedException]], carrying the status.
  * Anything else thrown means the relay did not answer, and the two are reported differently.
  */
trait ShareRelay:
  /** The URL this relay answers at — what a warning names when it does not. */
  def baseUrl: String

  /** Open a request of any type on the relay. For Deposit: ciphertext must be supplied (the encrypted share). For every
    * other type: ciphertext is absent.
    */
  def openShareRequest(
      secretId: UUID,
      recipientKey: Array[Byte],
      sealedLabel: Array[Byte],
      secretCreatedAt: Instant,
      transactionType: ShareTransactionType,
      ciphertext: Option[Array[Byte]],
      k: Option[Int] = None,
      n: Option[Int] = None,
      mimeType: Option[MimeType] = None,
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

  /** Delete a single request by id, and nothing else with it. */
  def deleteShareRequest(requestId: UUID): Unit

  // The signed rotate(K_old -> K_new) push. Grouped onto this trait rather than a separate
  // port: it's the same physical relay endpoint and the same BYOR per-contact routing as every
  // other ShareRelay call. deposplit.com's own backend keeps rotation pushes in a dedicated
  // key_rotations table/KeyRotations service for domain-purity reasons (no secretId, no consent
  // phase) that are about server-side schema shape, not about this client-side HTTP-calling
  // port, so no equivalent split is needed here.

  /** Pushes a signed rotation notice to one contact. `signature` must verify against the caller's own current verify
    * key (the relay's `oldVerifyKey`) over `value_objects.svo.PayloadCanonical.forRotation`. `newCipherSuite` is the
    * signing + key-agreement algorithm pairing `newVerifyKey`/`newEncKey` use.
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

  // The signed custodial-heartbeat push. Grouped onto this trait for the same reason as
  // the rotation methods above — same physical relay endpoint, same BYOR per-contact routing.

  /** Pushes (upserts) a signed heartbeat to one owner. `signature` must verify against the caller's own current Ed25519
    * key (the relay's `holderKey`) over `value_objects.svo.PayloadCanonical.forHeartbeat`, `emittedAt` included. The
    * same call covers the opt-out notice (`optedOut = true`).
    */
  def pushHeartbeat(
      ownerKey: Array[Byte],
      secretIds: Seq[UUID],
      optedOut: Boolean,
      emittedAt: Instant,
      signature: Array[Byte]
  ): Unit

  /** The unread heartbeat from each holder addressed to this device (the owner). */
  def listHeartbeats(): List[CustodyHeartbeat]

  /** Deletes a heartbeat once read — see `CustodyHeartbeat`. */
  def deleteHeartbeat(id: UUID): Unit
