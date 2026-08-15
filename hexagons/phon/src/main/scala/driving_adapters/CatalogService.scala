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
import driving_ports.CatalogManagement
import jakarta.inject.Inject
import value_objects.svo.Catalog

class CatalogService @Inject() (
    contactRepository: ContactRepository,
    secretRepository: SecretRepository,
    shareMetadataRepository: ShareMetadataRepository
) extends CatalogManagement:

  override def exportCatalog(): Catalog =
    Catalog(
      contacts = contactRepository.getAll(),
      secrets = secretRepository.getAll(),
      shareMetadata = shareMetadataRepository.getAll()
    )

  override def importCatalog(catalog: Catalog): Int =
    val existingContactIds = contactRepository.getAll().map(_.id).toSet
    var added = 0
    catalog.contacts.foreach { contact =>
      if !existingContactIds.contains(contact.id) then
        contactRepository.save(contact)
        added += 1
    }

    val existingSecretIds = secretRepository.getAll().map(_.id).toSet
    catalog.secrets.foreach { secret =>
      if !existingSecretIds.contains(secret.id) then secretRepository.save(secret)
    }

    val existingMetaIds = shareMetadataRepository.getAll().map(_.id).toSet
    catalog.shareMetadata.foreach { meta =>
      if !existingMetaIds.contains(meta.id) then shareMetadataRepository.save(meta)
    }

    added
