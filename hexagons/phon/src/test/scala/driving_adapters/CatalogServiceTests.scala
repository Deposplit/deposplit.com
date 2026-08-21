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
import driven_ports.SecretRepository
import driven_ports.ShareMetadataRepository
import value_objects.svo.Catalog
import value_objects.svo.Contact
import value_objects.svo.Secret
import value_objects.svo.SecretState
import value_objects.svo.ShareMetadata
import value_objects.svo.VerificationLevel

import java.time.Instant
import java.util.UUID

private class InMemoryContactRepositoryForCatalogTest extends ContactRepository:
  private var contacts: List[Contact] = Nil
  override def getAll(): List[Contact] = contacts
  override def getByEdKey(verifyKey: Array[Byte]): Option[Contact] = contacts.find(_.verifyKey.sameElements(verifyKey))
  override def getById(id: UUID): Option[Contact] = contacts.find(_.id == id)
  override def save(contact: Contact): Unit = contacts = contact :: contacts.filterNot(_.id == contact.id)
  override def delete(contactId: UUID): Unit = contacts = contacts.filterNot(_.id == contactId)

private class InMemorySecretRepositoryForCatalogTest extends SecretRepository:
  private var secrets: List[Secret] = Nil
  override def getAll(): List[Secret] = secrets
  override def save(secret: Secret): Unit = secrets = secret :: secrets.filterNot(_.id == secret.id)
  override def delete(secretId: UUID): Unit = secrets = secrets.filterNot(_.id == secretId)

private class InMemoryShareMetadataRepositoryForCatalogTest extends ShareMetadataRepository:
  private var metas: List[ShareMetadata] = Nil
  override def getAll(): List[ShareMetadata] = metas
  override def save(share: ShareMetadata): Unit = metas = share :: metas.filterNot(_.id == share.id)
  override def delete(shareId: UUID): Unit = metas = metas.filterNot(_.id == shareId)

class CatalogServiceTests extends munit.FunSuite:

  private def makeContact(name: String): Contact = Contact(
    id = UUID.randomUUID(),
    pseudonym = name,
    verifyKey = Array.fill(32)(0x01.toByte),
    encKey = Array.fill(32)(0x02.toByte),
    verificationLevel = VerificationLevel.VeryHigh,
    verifiedAt = Some(Instant.now()),
    addedAt = Instant.now()
  )

  test("exportCatalog then importCatalog round-trips contacts secrets and shareMetadata") {
    val contactRepo = InMemoryContactRepositoryForCatalogTest()
    val secretRepo = InMemorySecretRepositoryForCatalogTest()
    val metaRepo = InMemoryShareMetadataRepositoryForCatalogTest()
    val exporter = CatalogService(contactRepo, secretRepo, metaRepo)

    val contact = makeContact("alice")
    contactRepo.save(contact)
    val secret = Secret(UUID.randomUUID(), "test", 2, 3, Instant.now(), SecretState.Active)
    secretRepo.save(secret)
    val meta = ShareMetadata(UUID.randomUUID(), secret.id, contact.id)
    metaRepo.save(meta)

    val catalog = exporter.exportCatalog()

    val freshContactRepo = InMemoryContactRepositoryForCatalogTest()
    val freshSecretRepo = InMemorySecretRepositoryForCatalogTest()
    val freshMetaRepo = InMemoryShareMetadataRepositoryForCatalogTest()
    val importer = CatalogService(freshContactRepo, freshSecretRepo, freshMetaRepo)

    val added = importer.importCatalog(catalog)

    assertEquals(added, 1)
    assertEquals(freshContactRepo.getAll().map(_.id), List(contact.id))
    assertEquals(freshSecretRepo.getAll().map(_.id), List(secret.id))
    assertEquals(freshMetaRepo.getAll().map(_.id), List(meta.id))
  }

  test("importCatalog does not overwrite an existing local contact") {
    val contactRepo = InMemoryContactRepositoryForCatalogTest()
    val secretRepo = InMemorySecretRepositoryForCatalogTest()
    val metaRepo = InMemoryShareMetadataRepositoryForCatalogTest()
    val svc = CatalogService(contactRepo, secretRepo, metaRepo)

    val localContact = makeContact("locally-edited-name")
    contactRepo.save(localContact)
    val staleImportedVersion = localContact.copy(pseudonym = "stale-backup-name", verificationLevel = VerificationLevel.VeryLow)
    val catalog = Catalog(contacts = List(staleImportedVersion), secrets = Nil, shareMetadata = Nil)

    val added = svc.importCatalog(catalog)

    assertEquals(added, 0)
    assertEquals(contactRepo.getById(localContact.id).map(_.pseudonym), Some("locally-edited-name"))
  }
