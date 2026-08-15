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

import value_objects.svo.Contact
import value_objects.svo.HeldShare
import value_objects.svo.Secret
import value_objects.svo.ShareMetadata
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestType

import java.util.UUID

trait ShareManagement:
  // ─── Sender ────────────────────────────────────────────────────────────────
  def deposit(secret: Array[Byte], label: String, contacts: List[Contact], threshold: Int): Unit
  def listSecrets(): List[Secret]
  def syncDistributed(): Unit
  def listDistributed(): List[ShareMetadata]
  def listSentRequests(): List[ShareRequest]
  def requestAll(secretId: UUID): Unit
  def openRequest(shareId: UUID, requestType: ShareRequestType): ShareRequest
  /** Pure read (item 11) — collects k approved retrieve shares and decrypts them. Never tears
    * down local `ShareMetadata` or relay rows; use `discardSecret` for that.
    */
  def reconstruct(secretId: UUID): Array[Byte]
  /** Fans out a sender-initiated delete request to every known holder of secretId and flips the
    * Secret to Discarding immediately (before any holder responds).
    */
  def discardSecret(secretId: UUID): Unit
  /** Local-only teardown for a Discarding secret whose holders will never all respond (e.g. a
    * permanently dark holder). Does not wait for or require relay confirmation.
    */
  def forceForgetSecret(secretId: UUID): Unit

  // ─── Recipient ──────────────────────────────────────────────────────────────
  def syncInbox(): Unit
  def listHeld(): List[HeldShare]
  def listPendingRequests(): List[ShareRequest]
  def respond(requestId: UUID, approved: Boolean): Unit
  def deleteHeldShare(shareId: UUID): Unit
  def deleteAllHeldFromSender(contactId: UUID): Unit

  // ─── Identity recovery (item 8) — holder side ────────────────────────────
  /** Pushes a metadata-only report (no share bytes) for every HeldShare held from contactId back
    * to that contact, so a recovering owner can rebuild her ShareMetadata/Secret records. Call
    * after ContactManagement.updateContact has relinked the re-presented identity to the
    * existing contact.
    */
  def pushRecoveryMetadata(contactId: UUID): Unit
