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
import value_objects.svo.Contact
import value_objects.svo.VerificationLevel

import java.time.Instant
import java.util.UUID

private class InMemoryContactRepositoryForContactServiceTest extends ContactRepository:
  private var contacts: List[Contact] = Nil
  override def getAll(): List[Contact] = contacts
  override def getByEdKey(edPublicKey: Array[Byte]): Option[Contact] = contacts.find(_.edPublicKey.sameElements(edPublicKey))
  override def getById(id: UUID): Option[Contact] = contacts.find(_.id == id)
  override def save(contact: Contact): Unit = contacts = contact :: contacts.filterNot(_.id == contact.id)
  override def delete(contactId: UUID): Unit = contacts = contacts.filterNot(_.id == contactId)

// updateContact (item 8) — contact-update-in-place, preserving contactId, used both for benign
// key rotation and holder-driven recovery relinking. See deposplit.com/CLAUDE.md item 8.
class ContactServiceTests extends munit.FunSuite:

  private def makeContact(): Contact = Contact(
    id = UUID.randomUUID(),
    pseudonym = "bob",
    edPublicKey = Array.fill(32)(0x01.toByte),
    xPublicKey = Array.fill(32)(0x02.toByte),
    verificationLevel = VerificationLevel.VeryHigh,
    verifiedAt = Some(Instant.EPOCH),
    addedAt = Instant.EPOCH
  )

  test("updateContact preserves contactId while changing keys") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(repo)
    val original = makeContact()
    repo.save(original)
    val newEd = Array.fill(32)(0x03.toByte)
    val newX = Array.fill(32)(0x04.toByte)

    svc.updateContact(original.id, edPublicKey = Some(newEd), xPublicKey = Some(newX))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assertEquals(updated.id, original.id)
    assertEquals(updated.pseudonym, original.pseudonym)
    assert(updated.edPublicKey.sameElements(newEd))
    assert(updated.xPublicKey.sameElements(newX))
  }

  test("updateContact can change only one key") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(repo)
    val original = makeContact()
    repo.save(original)
    val newEd = Array.fill(32)(0x05.toByte)

    svc.updateContact(original.id, edPublicKey = Some(newEd))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assert(updated.edPublicKey.sameElements(newEd))
    assert(updated.xPublicKey.sameElements(original.xPublicKey))
  }

  test("updateContact throws for an unknown contactId") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(repo)

    intercept[IllegalStateException] {
      svc.updateContact(UUID.randomUUID(), edPublicKey = Some(Array.fill(32)(0x01.toByte)))
    }
  }

  // Item 9: an explicit verificationLevel (the rotation-processing downgrade) always wins over
  // the no-picker-UI VeryHigh default a bare key change would otherwise apply.
  test("updateContact honors an explicit verificationLevel instead of defaulting to VeryHigh on a key change") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(repo)
    val original = makeContact()
    repo.save(original)
    val newEd = Array.fill(32)(0x06.toByte)

    svc.updateContact(original.id, edPublicKey = Some(newEd), verificationLevel = Some(VerificationLevel.Low))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assert(updated.edPublicKey.sameElements(newEd))
    assertEquals(updated.verificationLevel, VerificationLevel.Low)
  }

  test("updateContact still defaults to VeryHigh on a key change when no explicit level is given") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(repo)
    val original = makeContact().copy(verificationLevel = VerificationLevel.Low)
    repo.save(original)

    svc.updateContact(original.id, edPublicKey = Some(Array.fill(32)(0x07.toByte)))

    assertEquals(repo.getById(original.id).map(_.verificationLevel), Some(VerificationLevel.VeryHigh))
  }

  // ── Item 10: stolen-key revocation ────────────────────────────────────────

  test("updateContact sets keyChangedAt only when keys actually change") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(repo)
    val original = makeContact()
    repo.save(original)
    assertEquals(original.keyChangedAt, None)

    svc.updateContact(original.id, verificationLevel = Some(VerificationLevel.High))
    assertEquals(repo.getById(original.id).flatMap(_.keyChangedAt), None)

    svc.updateContact(original.id, edPublicKey = Some(Array.fill(32)(0x08.toByte)))
    assert(repo.getById(original.id).flatMap(_.keyChangedAt).isDefined)
  }

  test("markKeyCompromised flags the contact's current key by default") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(repo)
    val original = makeContact()
    repo.save(original)

    svc.markKeyCompromised(original.id)

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assertEquals(updated.revokedEdKeys.size, 1)
    assert(updated.revokedEdKeys.head.sameElements(original.edPublicKey))
  }

  test("markKeyCompromised is idempotent for an already-flagged key") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(repo)
    val original = makeContact()
    repo.save(original.copy(revokedEdKeys = List(original.edPublicKey)))

    svc.markKeyCompromised(original.id)

    assertEquals(repo.getById(original.id).map(_.revokedEdKeys.size), Some(1))
  }

  test("markKeyCompromised can flag an explicit key other than the current one") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(repo)
    val original = makeContact()
    repo.save(original)
    val oldKey = Array.fill(32)(0x09.toByte)

    svc.markKeyCompromised(original.id, edPublicKey = Some(oldKey))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assertEquals(updated.revokedEdKeys.size, 1)
    assert(updated.revokedEdKeys.head.sameElements(oldKey))
    assert(!updated.revokedEdKeys.head.sameElements(original.edPublicKey))
  }
