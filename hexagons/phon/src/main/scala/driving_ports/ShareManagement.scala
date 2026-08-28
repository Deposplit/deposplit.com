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

import value_objects.svo.CipherSuite
import value_objects.svo.Contact
import value_objects.svo.HeldShare
import value_objects.svo.KeyConflict
import value_objects.svo.ReconstructionResult
import value_objects.svo.RegenerateIdentityResult
import value_objects.svo.Secret
import value_objects.svo.ShareMetadata
import value_objects.svo.ShareRequest
import value_objects.svo.ShareTransactionType

import java.util.UUID

trait ShareManagement:
  // ─── Sender ────────────────────────────────────────────────────────────────
  def deposit(secret: Array[Byte], label: String, contacts: List[Contact], threshold: Int): Unit
  def listSecrets(): List[Secret]
  def syncDistributed(): Unit
  def listDistributed(): List[ShareMetadata]
  def listSentRequests(): List[ShareRequest]
  def requestAll(secretId: UUID): Unit
  def openRequest(shareId: UUID, transactionType: ShareTransactionType): ShareRequest

  /** Pure read (item 11) — collects approved retrieval shares (possibly more than k, item 13) and decrypts them. Never
    * tears down local `ShareMetadata` or relay rows; use `discardSecret` for that. Cross-checks any surplus beyond k
    * for consistency (item 13) — throws rather than returning a guessed secret if the surplus can't be reconciled.
    */
  def reconstruct(secretId: UUID): ReconstructionResult

  /** Fans out a sender-initiated removal request to every known holder of secretId and flips the Secret to Discarding
    * immediately (before any holder responds).
    */
  def discardSecret(secretId: UUID): Unit

  /** Local-only teardown for a Discarding secret whose holders will never all respond (e.g. a permanently dark holder).
    * Does not wait for or require relay confirmation.
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
  /** Pushes a metadata-only report (no share bytes) for every HeldShare held from contactId back to that contact, so a
    * recovering owner can rebuild her ShareMetadata/Secret records. Call after ContactManagement.updateContact has
    * relinked the re-presented identity to the existing contact.
    */
  def pushRecoveryMetadata(contactId: UUID): Unit

  // ─── Item 9 — signed rotate(K_old -> K_new) push, client primitive only ─────
  /** Signs newVerifyKey/newEncKey with the device's *current* identity (which becomes oldVerifyKey on the wire) and
    * pushes one signed notice to contactId. Reused unchanged by regenerateIdentity() (item 9's "regenerate my own
    * identity" trigger). newCipherSuite (item 14) is the signing + key-agreement algorithm pairing
    * newVerifyKey/newEncKey use.
    */
  def pushRotation(
      contactId: UUID,
      newVerifyKey: Array[Byte],
      newEncKey: Array[Byte],
      newCipherSuite: CipherSuite
  ): Unit

  // ─── Item 10 — key conflicts (never auto-resolved), local-only, no relay involvement ────────
  def listKeyConflicts(): List[KeyConflict]
  def dismissKeyConflict(id: UUID): Unit

  // ─── Item 12 — custodial heartbeats, holder-side opt-out ────────────────────
  /** This device's own choice to stop (or resume) heartbeating contactId (who is the owner of shares this device holds
    * from them). Low-stakes and reversible, unlike marking a key compromised — no confirmation needed.
    */
  def setHeartbeatEmissionOptedOut(contactId: UUID, optedOut: Boolean): Unit

  // ─── Item 9 — the "regenerate my own identity" trigger (proactive rotation while still
  // holding the device and old keys — distinct from item 8's device-loss recovery) ────────────
  /** Best-effort drains the inbox/distributed state under the old identity, generates a fresh keypair, pushes a signed
    * rotation notice (via the existing pushRotation, unchanged) to every contact while still signing as the old
    * identity, then activates the new keypair locally. A contact whose push fails is not retried — same one-shot
    * semantics as pushRotation itself.
    */
  def regenerateIdentity(): RegenerateIdentityResult
