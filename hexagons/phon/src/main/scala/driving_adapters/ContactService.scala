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
import value_objects.svo.Contact
import value_objects.svo.VerificationLevel

import java.time.Instant
import java.util.UUID

class ContactService @Inject() (contactRepository: ContactRepository) extends ContactManagement:

  def listContacts(): List[Contact] =
    contactRepository.getAll()

  def addManually(pseudonym: String, edPublicKey: Array[Byte], xPublicKey: Array[Byte], relayBaseUrl: Option[String] = None): Unit =
    require(pseudonym.nonEmpty, "pseudonym must not be blank")
    require(edPublicKey.length == 32, "Ed25519 public key must be 32 bytes")
    require(xPublicKey.length == 32, "X25519 public key must be 32 bytes")
    val now = Instant.now()
    contactRepository.save(
      Contact(
        id = UUID.randomUUID(),
        pseudonym = pseudonym.strip(),
        edPublicKey = edPublicKey,
        xPublicKey = xPublicKey,
        verificationLevel = VerificationLevel.VeryLow,
        verifiedAt = Some(now),
        addedAt = now,
        relayBaseUrl = relayBaseUrl
      )
    )

  def addFromQr(pseudonym: String, edPublicKey: Array[Byte], xPublicKey: Array[Byte], relayBaseUrl: Option[String] = None): Unit =
    require(pseudonym.nonEmpty, "pseudonym must not be blank")
    require(edPublicKey.length == 32, "Ed25519 public key must be 32 bytes")
    require(xPublicKey.length == 32, "X25519 public key must be 32 bytes")
    val now = Instant.now()
    contactRepository.save(
      Contact(
        id = UUID.randomUUID(),
        pseudonym = pseudonym.strip(),
        edPublicKey = edPublicKey,
        xPublicKey = xPublicKey,
        verificationLevel = VerificationLevel.VeryHigh,
        verifiedAt = Some(now),
        addedAt = now,
        relayBaseUrl = relayBaseUrl
      )
    )

  def updateContact(
      contactId: UUID,
      edPublicKey: Option[Array[Byte]] = None,
      xPublicKey: Option[Array[Byte]] = None,
      verificationLevel: Option[VerificationLevel] = None
  ): Unit =
    val existing = contactRepository
      .getById(contactId)
      .getOrElse(throw IllegalStateException(s"Contact not found for id $contactId"))
    edPublicKey.foreach(k => require(k.length == 32, "Ed25519 public key must be 32 bytes"))
    xPublicKey.foreach(k => require(k.length == 32, "X25519 public key must be 32 bytes"))
    val changingKeys = edPublicKey.isDefined || xPublicKey.isDefined
    // A key change forces re-choosing the level fresh (item 8/10), never silently carrying the
    // old one forward. An explicit `verificationLevel` (item 9's rotation downgrade) always
    // wins; absent one, a key change defaults to the same VeryHigh addFromQr uses for its
    // analogous re-scan-in-person flow — phon has no picker UI (item 6's narrower scope).
    val newLevel = verificationLevel.orElse(if changingKeys then Some(VerificationLevel.VeryHigh) else None)
    contactRepository.save(
      existing.copy(
        edPublicKey = edPublicKey.getOrElse(existing.edPublicKey),
        xPublicKey = xPublicKey.getOrElse(existing.xPublicKey),
        verificationLevel = newLevel.getOrElse(existing.verificationLevel),
        verifiedAt = if changingKeys || verificationLevel.isDefined then Some(Instant.now()) else existing.verifiedAt,
        revokedEdKeys = existing.revokedEdKeys,
        keyChangedAt = if changingKeys then Some(Instant.now()) else existing.keyChangedAt
      )
    )

  def deleteContact(contactId: UUID): Unit =
    contactRepository.delete(contactId)

  // Item 10 — idempotent: a no-op if the key is already in revokedEdKeys.
  def markKeyCompromised(contactId: UUID, edPublicKey: Option[Array[Byte]] = None): Unit =
    val existing = contactRepository
      .getById(contactId)
      .getOrElse(throw IllegalStateException(s"Contact not found for id $contactId"))
    val keyToFlag = edPublicKey.getOrElse(existing.edPublicKey)
    if !existing.revokedEdKeys.exists(_.sameElements(keyToFlag)) then
      contactRepository.save(existing.copy(revokedEdKeys = keyToFlag :: existing.revokedEdKeys))
