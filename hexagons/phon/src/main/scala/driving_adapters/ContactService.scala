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

  def addManually(pseudonym: String, edPublicKey: Array[Byte], xPublicKey: Array[Byte]): Unit =
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
        verificationLevel = VerificationLevel.Unverified,
        verifiedAt = None,
        addedAt = now
      )
    )

  def addFromQr(pseudonym: String, edPublicKey: Array[Byte], xPublicKey: Array[Byte]): Unit =
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
        verificationLevel = VerificationLevel.Verified,
        verifiedAt = Some(now),
        addedAt = now
      )
    )

  def deleteContact(contactId: UUID): Unit =
    contactRepository.delete(contactId)
