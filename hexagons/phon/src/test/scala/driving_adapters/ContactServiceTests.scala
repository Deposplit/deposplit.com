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

import driven_ports.ContactRelinkRepository
import driven_ports.ContactRepository
import driven_ports.IdentityStore
import value_objects.svo.CipherSuite
import value_objects.svo.Contact
import value_objects.svo.ContactRelink
import value_objects.svo.VerificationLevel

import java.time.Instant
import java.util.UUID

private class InMemoryContactRepositoryForContactServiceTest extends ContactRepository:
  private var contacts: List[Contact] = Nil
  override def getAll(): List[Contact] = contacts
  override def getByVerifyKey(verifyKey: Array[Byte]): Option[Contact] =
    contacts.find(_.verifyKey.sameElements(verifyKey))
  override def getById(id: UUID): Option[Contact] = contacts.find(_.id == id)
  override def save(contact: Contact): Unit = contacts = contact :: contacts.filterNot(_.id == contact.id)
  override def delete(contactId: UUID): Unit = contacts = contacts.filterNot(_.id == contactId)

private class InMemoryContactRelinkRepositoryForContactServiceTest extends ContactRelinkRepository:
  private var relinks: List[ContactRelink] = Nil
  override def getAll(): List[ContactRelink] = relinks
  override def get(contactId: UUID): Option[ContactRelink] = relinks.find(_.contactId == contactId)
  override def save(relink: ContactRelink): Unit =
    relinks = relink :: relinks.filterNot(_.contactId == relink.contactId)

/** Only identityCreatedAt() is read by ContactService; the rest of the port is never reached. */
private class FakeIdentityStoreForContactServiceTest(createdAt: Option[Instant] = None) extends IdentityStore:
  override def isRegistered(): Boolean = createdAt.isDefined
  override def save(
      pseudonym: String,
      verifyKey: Array[Byte],
      signKey: Array[Byte],
      encKey: Array[Byte],
      decKey: Array[Byte]
  ): Unit = ()
  override def rotate(
      verifyKey: Array[Byte],
      signKey: Array[Byte],
      encKey: Array[Byte],
      decKey: Array[Byte]
  ): Unit = ()
  override def pseudonym(): String = ""
  override def identityCreatedAt(): Option[Instant] = createdAt
  override def verifyKey(): Option[Array[Byte]] = None
  override def signKey(): Array[Byte] = Array.emptyByteArray
  override def encKey(): Option[Array[Byte]] = None
  override def decKey(): Array[Byte] = Array.emptyByteArray
  override def previousDecKey(): Option[Array[Byte]] = None

// updateContact — contact-update-in-place, preserving contactId, used both for benign key
// rotation and holder-driven recovery relinking.
class ContactServiceTests extends munit.FunSuite:

  private def makeContact(): Contact = Contact(
    id = UUID.randomUUID(),
    pseudonym = "bob",
    verifyKey = Array.fill(32)(0x01.toByte),
    encKey = Array.fill(32)(0x02.toByte),
    verificationLevel = VerificationLevel.VeryHigh,
    verifiedAt = Some(Instant.EPOCH),
    addedAt = Instant.EPOCH
  )

  test("updateContact preserves contactId while changing keys") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original)
    val newEd = Array.fill(32)(0x03.toByte)
    val newX = Array.fill(32)(0x04.toByte)

    svc.updateContact(original.id, verifyKey = Some(newEd), encKey = Some(newX))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assertEquals(updated.id, original.id)
    assertEquals(updated.pseudonym, original.pseudonym)
    assert(updated.verifyKey.sameElements(newEd))
    assert(updated.encKey.sameElements(newX))
  }

  test("updateContact can change only one key") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original)
    val newEd = Array.fill(32)(0x05.toByte)

    svc.updateContact(original.id, verifyKey = Some(newEd))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assert(updated.verifyKey.sameElements(newEd))
    assert(updated.encKey.sameElements(original.encKey))
  }

  test("updateContact throws for an unknown contactId") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )

    intercept[IllegalStateException] {
      svc.updateContact(UUID.randomUUID(), verifyKey = Some(Array.fill(32)(0x01.toByte)))
    }
  }

  // An explicit verificationLevel (the rotation-processing downgrade) always wins over
  // the no-picker-UI VeryHigh default a bare key change would otherwise apply.
  test("updateContact honors an explicit verificationLevel instead of defaulting to VeryHigh on a key change") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original)
    val newEd = Array.fill(32)(0x06.toByte)

    svc.updateContact(original.id, verifyKey = Some(newEd), verificationLevel = Some(VerificationLevel.Low))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assert(updated.verifyKey.sameElements(newEd))
    assertEquals(updated.verificationLevel, VerificationLevel.Low)
  }

  test("updateContact still defaults to VeryHigh on a key change when no explicit level is given") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact().copy(verificationLevel = VerificationLevel.Low)
    repo.save(original)

    svc.updateContact(original.id, verifyKey = Some(Array.fill(32)(0x07.toByte)))

    assertEquals(repo.getById(original.id).map(_.verificationLevel), Some(VerificationLevel.VeryHigh))
  }

  // ── Stolen-key revocation ─────────────────────────────────────────────────

  test("updateContact sets keyChangedAt only when keys actually change") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original)
    assertEquals(original.keyChangedAt, None)

    svc.updateContact(original.id, verificationLevel = Some(VerificationLevel.High))
    assertEquals(repo.getById(original.id).flatMap(_.keyChangedAt), None)

    svc.updateContact(original.id, verifyKey = Some(Array.fill(32)(0x08.toByte)))
    assert(repo.getById(original.id).flatMap(_.keyChangedAt).isDefined)
  }

  test("markKeyCompromised flags the contact's current key by default") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original)

    svc.markKeyCompromised(original.id)

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assertEquals(updated.revokedVerifyKeys.size, 1)
    assert(updated.revokedVerifyKeys.head.sameElements(original.verifyKey))
  }

  test("markKeyCompromised is idempotent for an already-flagged key") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original.copy(revokedVerifyKeys = List(original.verifyKey)))

    svc.markKeyCompromised(original.id)

    assertEquals(repo.getById(original.id).map(_.revokedVerifyKeys.size), Some(1))
  }

  test("markKeyCompromised can flag an explicit key other than the current one") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original)
    val oldKey = Array.fill(32)(0x09.toByte)

    svc.markKeyCompromised(original.id, verifyKey = Some(oldKey))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assertEquals(updated.revokedVerifyKeys.size, 1)
    assert(updated.revokedVerifyKeys.head.sameElements(oldKey))
    assert(!updated.revokedVerifyKeys.head.sameElements(original.verifyKey))
  }

  // ── Crypto agility: cipher suite threading + suite-aware length validation ──────────────────

  test("addFromQr stores the asserted cipherSuite") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )

    svc.addFromQr("bob", Array.fill(32)(0x01.toByte), Array.fill(32)(0x02.toByte), CipherSuite.current)

    assertEquals(repo.getAll().head.cipherSuite, CipherSuite.current)
  }

  test("addManually defaults to the current cipherSuite") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )

    svc.addManually("bob", Array.fill(32)(0x01.toByte), Array.fill(32)(0x02.toByte))

    assertEquals(repo.getAll().head.cipherSuite, CipherSuite.current)
  }

  test("addFromQr rejects a verify key whose length does not match the asserted cipherSuite") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )

    intercept[IllegalArgumentException] {
      svc.addFromQr("bob", Array.fill(16)(0x01.toByte), Array.fill(32)(0x02.toByte), CipherSuite.current)
    }
  }

  test("updateContact rejects a new key whose length does not match the effective cipherSuite") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original)

    intercept[IllegalArgumentException] {
      svc.updateContact(original.id, verifyKey = Some(Array.fill(16)(0x01.toByte)))
    }
  }

  test("updateContact forces a fresh verification level on a cipherSuite-only change") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact().copy(verificationLevel = VerificationLevel.Low)
    repo.save(original)

    // No explicit level given, but this hexagon's no-picker-UI default kicks in (same as a key
    // change) rather than throwing — unlike Android/iOS, which require an explicit level.
    svc.updateContact(original.id, cipherSuite = Some(CipherSuite.current))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assertEquals(updated.cipherSuite, CipherSuite.current)
    assertEquals(updated.verificationLevel, VerificationLevel.VeryHigh)
    assert(updated.keyChangedAt.isDefined)
  }

  // ── Local contact nicknames ───────────────────────────────────────────────

  test("renameContact sets a nickname without touching keys, level, or keyChangedAt") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original)

    svc.renameContact(original.id, Some("Coworker Paul"))

    val updated = repo.getById(original.id).getOrElse(fail("contact missing"))
    assertEquals(updated.nickname, Some("Coworker Paul"))
    assertEquals(updated.pseudonym, original.pseudonym)
    assert(updated.verifyKey.sameElements(original.verifyKey))
    assert(updated.encKey.sameElements(original.encKey))
    assertEquals(updated.verificationLevel, original.verificationLevel)
    assertEquals(updated.verifiedAt, original.verifiedAt)
    assertEquals(updated.keyChangedAt, None)
  }

  test("renameContact trims and collapses a blank nickname to None") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact()
    repo.save(original)

    svc.renameContact(original.id, Some("  Paul  "))
    assertEquals(repo.getById(original.id).flatMap(_.nickname), Some("Paul"))

    svc.renameContact(original.id, Some("   "))
    assertEquals(repo.getById(original.id).flatMap(_.nickname), None)
  }

  test("renameContact can clear an existing nickname") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )
    val original = makeContact().copy(nickname = Some("Paul"))
    repo.save(original)

    svc.renameContact(original.id, None)

    assertEquals(repo.getById(original.id).flatMap(_.nickname), None)
  }

  test("renameContact throws for an unknown contactId") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )

    intercept[IllegalStateException] {
      svc.renameContact(UUID.randomUUID(), Some("Paul"))
    }
  }

  test("addManually and addFromQr trim and normalize the nickname") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )

    svc.addManually("bob", Array.fill(32)(0x01.toByte), Array.fill(32)(0x02.toByte), nickname = Some("  Bobby  "))
    svc.addFromQr(
      "carol",
      Array.fill(32)(0x03.toByte),
      Array.fill(32)(0x04.toByte),
      CipherSuite.current,
      nickname = Some("   ")
    )

    val contacts = repo.getAll()
    assertEquals(contacts.find(_.pseudonym == "bob").flatMap(_.nickname), Some("Bobby"))
    assertEquals(contacts.find(_.pseudonym == "carol").flatMap(_.nickname), None)
  }

  test("addManually and addFromQr default the nickname to None when omitted") {
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val svc = ContactService(
      repo,
      FakeIdentityStoreForContactServiceTest(),
      InMemoryContactRelinkRepositoryForContactServiceTest()
    )

    svc.addManually("bob", Array.fill(32)(0x01.toByte), Array.fill(32)(0x02.toByte))

    assertEquals(repo.getAll().head.nickname, None)
  }

  // ---------------------------------------------------------------------------
  // contactsAwaitingRelink() — who still holds a key this device no longer signs with
  // ---------------------------------------------------------------------------

  private val identityBorn: Instant = Instant.parse("2026-06-01T00:00:00Z")

  private def awaitingSetup(identityCreatedAt: Option[Instant]) =
    val repo = InMemoryContactRepositoryForContactServiceTest()
    val relinks = InMemoryContactRelinkRepositoryForContactServiceTest()
    val svc = ContactService(repo, FakeIdentityStoreForContactServiceTest(identityCreatedAt), relinks)
    (svc, repo, relinks)

  test("a contact added before the current identity is awaiting relink") {
    val (svc, repo, _) = awaitingSetup(Some(identityBorn))
    val older = makeContact().copy(addedAt = identityBorn.minusSeconds(60))
    repo.save(older)
    assertEquals(svc.contactsAwaitingRelink().map(_.id), List(older.id))
  }

  test("a contact added after the current identity is not") {
    val (svc, repo, _) = awaitingSetup(Some(identityBorn))
    repo.save(makeContact().copy(addedAt = identityBorn.plusSeconds(60)))
    assert(svc.contactsAwaitingRelink().isEmpty)
  }

  // Anything arriving from a contact is proof, since the relay only returns rows addressed to the caller's current key.
  test("a relink recorded since the current identity clears the contact") {
    val (svc, repo, _) = awaitingSetup(Some(identityBorn))
    val older = makeContact().copy(addedAt = identityBorn.minusSeconds(60))
    repo.save(older)
    svc.markRelinked(older.id)
    assert(svc.contactsAwaitingRelink().isEmpty)
  }

  // A relink from before this identity existed was to the key that is gone, so it proves nothing.
  test("a relink older than the current identity does not clear the contact") {
    val (svc, repo, relinks) = awaitingSetup(Some(identityBorn))
    val older = makeContact().copy(addedAt = identityBorn.minusSeconds(120))
    repo.save(older)
    relinks.save(ContactRelink(older.id, identityBorn.minusSeconds(60)))
    assertEquals(svc.contactsAwaitingRelink().map(_.id), List(older.id))
  }

  // No recorded start is no basis to judge — flagging every contact on a guess would be a false alarm on a device that
  // never lost anything.
  test("an unrecorded identity start puts nobody on the list") {
    val (svc, repo, _) = awaitingSetup(None)
    repo.save(makeContact().copy(addedAt = Instant.EPOCH))
    assert(svc.contactsAwaitingRelink().isEmpty)
  }

  test("markRelinked is idempotent") {
    val (svc, repo, relinks) = awaitingSetup(Some(identityBorn))
    val older = makeContact().copy(addedAt = identityBorn.minusSeconds(60))
    repo.save(older)
    svc.markRelinked(older.id)
    svc.markRelinked(older.id)
    assertEquals(relinks.getAll().size, 1)
  }
