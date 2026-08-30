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

package driving_adapters

import driven_ports.ContactRepository
import driving_ports.ContactManagement
import jakarta.inject.Inject
import value_objects.svo.CipherSuite
import value_objects.svo.Contact
import value_objects.svo.VerificationLevel

import java.time.Instant
import java.util.UUID

class ContactService @Inject() (contactRepository: ContactRepository) extends ContactManagement:

  def listContacts(): List[Contact] =
    contactRepository.getAll()

  // No cipherSuite parameter: manual entry has no wire payload to read one from, and only one
  // suite exists to assume — see ContactManagement.addManually.
  def addManually(
      pseudonym: String,
      verifyKey: Array[Byte],
      encKey: Array[Byte],
      relayBaseUrl: Option[String] = None,
      nickname: Option[String] = None
  ): Unit =
    val cipherSuite = CipherSuite.current
    require(pseudonym.nonEmpty, "pseudonym must not be blank")
    require(
      verifyKey.length == cipherSuite.verifyKeyLength,
      s"Verify key must be ${cipherSuite.verifyKeyLength} bytes for $cipherSuite"
    )
    require(
      encKey.length == cipherSuite.encKeyLength,
      s"Enc key must be ${cipherSuite.encKeyLength} bytes for $cipherSuite"
    )
    val now = Instant.now()
    contactRepository.save(
      Contact(
        id = UUID.randomUUID(),
        pseudonym = pseudonym.strip(),
        verifyKey = verifyKey,
        encKey = encKey,
        verificationLevel = VerificationLevel.VeryLow,
        verifiedAt = Some(now),
        addedAt = now,
        relayBaseUrl = relayBaseUrl,
        cipherSuite = cipherSuite,
        nickname = normalizeNickname(nickname)
      )
    )

  def addFromQr(
      pseudonym: String,
      verifyKey: Array[Byte],
      encKey: Array[Byte],
      cipherSuite: CipherSuite,
      relayBaseUrl: Option[String] = None,
      nickname: Option[String] = None
  ): Unit =
    require(pseudonym.nonEmpty, "pseudonym must not be blank")
    require(
      verifyKey.length == cipherSuite.verifyKeyLength,
      s"Verify key must be ${cipherSuite.verifyKeyLength} bytes for $cipherSuite"
    )
    require(
      encKey.length == cipherSuite.encKeyLength,
      s"Enc key must be ${cipherSuite.encKeyLength} bytes for $cipherSuite"
    )
    val now = Instant.now()
    contactRepository.save(
      Contact(
        id = UUID.randomUUID(),
        pseudonym = pseudonym.strip(),
        verifyKey = verifyKey,
        encKey = encKey,
        verificationLevel = VerificationLevel.VeryHigh,
        verifiedAt = Some(now),
        addedAt = now,
        relayBaseUrl = relayBaseUrl,
        cipherSuite = cipherSuite,
        nickname = normalizeNickname(nickname)
      )
    )

  def updateContact(
      contactId: UUID,
      verifyKey: Option[Array[Byte]] = None,
      encKey: Option[Array[Byte]] = None,
      cipherSuite: Option[CipherSuite] = None,
      verificationLevel: Option[VerificationLevel] = None
  ): Unit =
    val existing = contactRepository
      .getById(contactId)
      .getOrElse(throw IllegalStateException(s"Contact not found for id $contactId"))
    val effectiveSuite = cipherSuite.getOrElse(existing.cipherSuite)
    verifyKey.foreach(k =>
      require(
        k.length == effectiveSuite.verifyKeyLength,
        s"Verify key must be ${effectiveSuite.verifyKeyLength} bytes for $effectiveSuite"
      )
    )
    encKey.foreach(k =>
      require(
        k.length == effectiveSuite.encKeyLength,
        s"Enc key must be ${effectiveSuite.encKeyLength} bytes for $effectiveSuite"
      )
    )
    // A cipher-suite-only change (no key-value change) forces the same fresh-level rule as a key
    // change: it's still continuity of key control, not a fresh personhood check.
    val changingIdentity = verifyKey.isDefined || encKey.isDefined || cipherSuite.isDefined
    // A key or cipher-suite change forces re-choosing the level fresh, never silently carrying
    // the old one forward. An explicit `verificationLevel` (the rotation downgrade) always wins;
    // absent one, a change defaults to the same VeryHigh addFromQr uses for its analogous
    // re-scan-in-person flow — phon has no verification-level picker UI.
    val newLevel = verificationLevel.orElse(if changingIdentity then Some(VerificationLevel.VeryHigh) else None)
    contactRepository.save(
      existing.copy(
        verifyKey = verifyKey.getOrElse(existing.verifyKey),
        encKey = encKey.getOrElse(existing.encKey),
        cipherSuite = effectiveSuite,
        verificationLevel = newLevel.getOrElse(existing.verificationLevel),
        verifiedAt =
          if changingIdentity || verificationLevel.isDefined then Some(Instant.now()) else existing.verifiedAt,
        revokedVerifyKeys = existing.revokedVerifyKeys,
        keyChangedAt = if changingIdentity then Some(Instant.now()) else existing.keyChangedAt
      )
    )

  // Deliberately separate from updateContact: never touches keys, cipherSuite,
  // verificationLevel, verifiedAt, or keyChangedAt. Pass None to clear an existing nickname.
  def renameContact(contactId: UUID, nickname: Option[String]): Unit =
    val existing = contactRepository
      .getById(contactId)
      .getOrElse(throw IllegalStateException(s"Contact not found for id $contactId"))
    contactRepository.save(existing.copy(nickname = normalizeNickname(nickname)))

  def deleteContact(contactId: UUID): Unit =
    contactRepository.delete(contactId)

  // Idempotent: a no-op if the key is already in revokedVerifyKeys.
  def markKeyCompromised(contactId: UUID, verifyKey: Option[Array[Byte]] = None): Unit =
    val existing = contactRepository
      .getById(contactId)
      .getOrElse(throw IllegalStateException(s"Contact not found for id $contactId"))
    val keyToFlag = verifyKey.getOrElse(existing.verifyKey)
    if !existing.revokedVerifyKeys.exists(_.sameElements(keyToFlag)) then
      contactRepository.save(existing.copy(revokedVerifyKeys = keyToFlag :: existing.revokedVerifyKeys))

  // Trim, then collapse blank to None. Lives here (not the UI layer) so every
  // caller — UI, tests, a future relink flow — gets consistent normalization for free.
  private def normalizeNickname(nickname: Option[String]): Option[String] =
    nickname.map(_.strip()).filter(_.nonEmpty)
