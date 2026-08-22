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
  // nickname (item 15) lets a nickname be set at add-time rather than only via a later
  // renameContact call; it is purely local and never transmitted anywhere.
  def addManually(pseudonym: String, verifyKey: Array[Byte], encKey: Array[Byte], relayBaseUrl: Option[String] = None, nickname: Option[String] = None): Unit
  // cipherSuite (item 14) is required here (unlike addManually) because the QR/link payload is
  // exactly where this self-describing fact comes from — manual entry has no wire payload to read
  // one from, so addManually assumes today's one suite instead. nickname (item 15) is likewise
  // not sourced from the QR payload — it is purely local — so it defaults to None here too.
  def addFromQr(pseudonym: String, verifyKey: Array[Byte], encKey: Array[Byte], cipherSuite: CipherSuite, relayBaseUrl: Option[String] = None, nickname: Option[String] = None): Unit
  /** Updates an existing contact in place, preserving contactId — never delete-and-re-add, which
    * would mint a fresh id and orphan any HeldShare/ShareMetadata rows anchored to it. See
    * deposplit.com/CLAUDE.md "What is next" item 8.
    *
    * `verificationLevel` is `None` by default: when the keys or cipher suite change (item 14
    * extends this to a suite-only change too) and no explicit level is given, this hexagon (no
    * picker UI — item 6's narrower phon scope) defaults to `VeryHigh`, mirroring `addFromQr`'s
    * in-person-flow default. Item 9's rotation-processing supplies an explicit level
    * (`min(old, Low)` — a signed rotation proves key continuity, not fresh personhood, so it must
    * never default to the same `VeryHigh` a human re-scan would earn).
    */
  def updateContact(contactId: UUID, verifyKey: Option[Array[Byte]] = None, encKey: Option[Array[Byte]] = None, cipherSuite: Option[CipherSuite] = None, verificationLevel: Option[VerificationLevel] = None): Unit
  /** Item 15 — deliberately separate from `updateContact`: a rename is not an identity change,
    * so it must never trigger `updateContact`'s fresh-verification-level requirement. Pass `None`
    * to clear an existing nickname.
    */
  def renameContact(contactId: UUID, nickname: Option[String]): Unit
  def deleteContact(contactId: UUID): Unit
  /** Item 10 — flags a verify key into the contact's revokedEdKeys history, out-of-band-
    * triggered (the user has some independent reason to believe it was stolen). Defaults to the
    * contact's *current* verifyKey when verifyKey is None. From this point, any signed rotation
    * notice claiming continuity from that key is refused auto-accept; only a fresh
    * human-verified relink can move the contact forward. Idempotent — a no-op if already flagged.
    */
  def markKeyCompromised(contactId: UUID, verifyKey: Option[Array[Byte]] = None): Unit
