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
import value_objects.svo.CipherSuite
import value_objects.svo.Contact
import value_objects.svo.VerificationLevel

trait ContactManagement:
  def listContacts(): List[Contact]

  /** Contacts who still hold a key this device no longer signs with, and so cannot address it any more. A contact added
    * before the current identity was established has by construction never seen it, unless something has arrived from
    * them since — the relay only returns rows addressed to the caller's current key, so receiving anything at all is
    * proof they relinked.
    *
    * Empty after a rotation, which propagates on its own; this is for the case that cannot, where the keys were lost
    * and the rotation notice could never be signed. Empty too on a device with no recorded `identityCreatedAt`, rather
    * than flagging every contact on a guess.
    */
  def contactsAwaitingRelink(): List[Contact]

  /** Records that this contact has relinked, when nothing will arrive to prove it — a contact who holds no share and
    * sends nothing produces no evidence, so without this the list could never empty. Idempotent.
    */
  def markRelinked(contactId: UUID): Unit
  // nickname lets a nickname be set at add-time rather than only via a later
  // renameContact call; it is purely local and never transmitted anywhere.
  def addManually(
      pseudonym: String,
      verifyKey: Array[Byte],
      encKey: Array[Byte],
      relayBaseUrl: Option[String] = None,
      nickname: Option[String] = None
  ): Unit
  // cipherSuite is required here (unlike addManually) because the QR/link payload is
  // exactly where this self-describing fact comes from — manual entry has no wire payload to read
  // one from, so addManually assumes today's one suite instead. nickname is likewise
  // not sourced from the QR payload — it is purely local — so it defaults to None here too.
  def addFromQr(
      pseudonym: String,
      verifyKey: Array[Byte],
      encKey: Array[Byte],
      cipherSuite: CipherSuite,
      relayBaseUrl: Option[String] = None,
      nickname: Option[String] = None
  ): Unit

  /** Updates an existing contact in place, preserving contactId — never delete-and-re-add, which would mint a fresh id
    * and orphan any HeldShare/ShareMetadata rows anchored to it.
    *
    * `verificationLevel` is `None` by default: when the keys or cipher suite change (a suite-only change counts too)
    * and no explicit level is given, this hexagon (which has no verification-level picker UI) defaults to `VeryHigh`,
    * mirroring `addFromQr`'s in-person-flow default. Rotation-processing supplies an explicit level (`min(old, Low)` —
    * a signed rotation proves key continuity, not fresh personhood, so it must never default to the same `VeryHigh` a
    * human re-scan would earn).
    */
  def updateContact(
      contactId: UUID,
      verifyKey: Option[Array[Byte]] = None,
      encKey: Option[Array[Byte]] = None,
      cipherSuite: Option[CipherSuite] = None,
      verificationLevel: Option[VerificationLevel] = None
  ): Unit

  /** Deliberately separate from `updateContact`: a rename is not an identity change, so it must never trigger
    * `updateContact`'s fresh-verification-level requirement. Pass `None` to clear an existing nickname.
    */
  def renameContact(contactId: UUID, nickname: Option[String]): Unit
  def deleteContact(contactId: UUID): Unit

  /** Flags a verify key into the contact's revokedVerifyKeys history, out-of-band-triggered (the user has some
    * independent reason to believe it was stolen). Defaults to the contact's *current* verifyKey when verifyKey is
    * None. From this point, any signed rotation notice claiming continuity from that key is refused auto-accept; only a
    * fresh human-verified relink can move the contact forward. Idempotent — a no-op if already flagged.
    */
  def markKeyCompromised(contactId: UUID, verifyKey: Option[Array[Byte]] = None): Unit
