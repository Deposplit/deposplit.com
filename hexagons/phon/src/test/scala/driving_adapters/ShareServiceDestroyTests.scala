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

import value_objects.svo.*

import java.time.Instant
import java.util.UUID

/** The far end of a secret's life: `destroySecret` asks, the holders answer, and the record goes away only once they
  * all have.
  *
  * This is reconciled from the *answered removal row*, which is the only place the destruction is ever recorded —
  * approving one makes the relay sweep the rest of that holder's rows for the secret, so nothing else survives to read
  * and an absence would say nothing at all. The double below models that sweep for the same reason: a double that kept
  * every row is what hid the transition failing to fire, because the client sat waiting for a row the relay had deleted
  * and nothing anywhere went red.
  */
class ShareServiceDestroyTests extends munit.FunSuite:

  private val aliceKeys = TestKeyPair.generate()
  private val charlieKeys = TestKeyPair.generate()

  private val aliceContact = Contact(
    id = UUID.randomUUID(),
    pseudonym = "alice",
    verifyKey = aliceKeys.publicKey,
    encKey = Array.fill(32)(0x01.toByte),
    verificationLevel = VerificationLevel.VeryHigh,
    verifiedAt = None,
    addedAt = Instant.now()
  )
  private val charlieContact = aliceContact.copy(
    id = UUID.randomUUID(),
    pseudonym = "charlie",
    verifyKey = charlieKeys.publicKey
  )

  private val secretId = UUID.randomUUID()
  private val secretCreatedAt = Instant.now()

  /** A relay double that keeps the two behaviours this flow turns on: listings honour their filters, and approving a
    * removal sweeps that holder's other rows for the secret while keeping the answer.
    */
  private class CascadingFakeShareRelay extends FakeShareRelay:
    override def listShareRequests(
        role: Role,
        transactionType: Option[ShareTransactionType],
        state: Option[ShareRequestState]
    ): List[ShareRequest] =
      pending.filter(r => transactionType.forall(_ == r.transactionType) && state.forall(_ == r.state))

    override def deleteShareRequest(requestId: UUID): Unit =
      deletedRequestIds :+= requestId
      pending = pending.filterNot(_.id == requestId)

    /** What the relay does when a holder approves: the answer is stamped and kept, everything else for that (secretId,
      * holder) pair goes — the deposit above all, which may still be holding ciphertext.
      */
    def approveRemoval(row: ShareRequest, holder: TestKeyPair): Unit =
      val signature = holder.sign(PayloadCanonical.forRespond(row.id, approved = true, ciphertext = None))
      val answered = row.copy(
        state = ShareRequestState.Approved,
        respondedAt = Some(Instant.now()),
        recipientSignature = Some(signature)
      )
      pending = answered :: pending.filterNot(r =>
        r.id == row.id || (r.secretId == row.secretId && r.recipientKey.sameElements(row.recipientKey))
      )

    def denyRemoval(row: ShareRequest, holder: TestKeyPair): Unit =
      val signature = holder.sign(PayloadCanonical.forRespond(row.id, approved = false, ciphertext = None))
      val answered = row.copy(
        state = ShareRequestState.Denied,
        respondedAt = Some(Instant.now()),
        recipientSignature = Some(signature)
      )
      pending = answered :: pending.filterNot(_.id == row.id)

  private def rowFor(
      holderKey: Array[Byte],
      transactionType: ShareTransactionType,
      state: ShareRequestState
  ): ShareRequest =
    ShareRequest(
      id = UUID.randomUUID(),
      secretId = secretId,
      senderKey = Array.emptyByteArray,
      recipientKey = holderKey,
      label = "destroy test",
      secretCreatedAt = secretCreatedAt,
      transactionType = transactionType,
      state = state,
      requestedAt = Instant.now(),
      respondedAt = None,
      ciphertext = None,
      k = None,
      n = None,
      mimeType = None,
      senderSignature = Array.emptyByteArray,
      recipientSignature = None
    )

  /** A sender-side service holding one 2-of-2 secret already Destroying, with a removal outstanding at each holder —
    * the state `destroySecret` leaves behind. Each `ShareMetadata` is keyed by its deposit row's id, as the real
    * deposit flow keys it.
    */
  private def destroyingService(
      relay: CascadingFakeShareRelay
  ): (ShareService, FakeShareMetadataRepository, FakeSecretRepository, Map[String, ShareRequest]) =
    val identityStore = InMemoryForgettableIdentityStore()
    val identity = IdentityService(identityStore)
    identity.register("owner")
    val contactRepo = FakeContactRepository(List(aliceContact, charlieContact))
    val metaRepo = FakeShareMetadataRepository()
    val secretRepo = FakeSecretRepository()
    secretRepo.save(
      Secret(
        id = secretId,
        label = "destroy test",
        mimeType = MimeType.Default,
        k = 2,
        n = 2,
        secretCreatedAt = secretCreatedAt,
        state = SecretState.Destroying
      )
    )
    val rows = List(aliceContact -> aliceKeys, charlieContact -> charlieKeys).flatMap { (contact, _) =>
      val deposit = rowFor(contact.verifyKey, ShareTransactionType.Deposit, ShareRequestState.Approved)
      val removal = rowFor(contact.verifyKey, ShareTransactionType.Removal, ShareRequestState.Pending)
      metaRepo.save(ShareMetadata(id = deposit.id, secretId = secretId, contactId = contact.id))
      List(s"${contact.pseudonym}.deposit" -> deposit, s"${contact.pseudonym}.removal" -> removal)
    }.toMap
    relay.pending = rows.values.toList
    val svc = ShareService(
      relayResolver = FixedShareRelayResolver(relay),
      encryption = NoOpShareEncryption,
      shareRepository = FakeShareRepository(),
      shareMetadataRepository = metaRepo,
      secretRepository = secretRepo,
      contactRepository = contactRepo,
      contactManagement =
        ContactService(contactRepo, identityStore, InMemoryContactRelinkRepositoryForShareServiceTests()),
      keyConflictRepository = FakeKeyConflictRepository(),
      retainedDepositRepository = FakeRetainedDepositRepository(),
      identity = identity
    )
    (svc, metaRepo, secretRepo, rows)

  test("A holder who approves their removal is dropped, and the secret waits for the other one") {
    val relay = CascadingFakeShareRelay()
    val (svc, metaRepo, secretRepo, rows) = destroyingService(relay)

    relay.approveRemoval(rows("alice.removal"), aliceKeys)
    svc.syncDistributed()

    assertEquals(metaRepo.getAll().map(_.contactId), List(charlieContact.id))
    assertEquals(secretRepo.getAll().map(_.state), List(SecretState.Destroying))
  }

  test("The secret is gone once the last holder has answered") {
    val relay = CascadingFakeShareRelay()
    val (svc, metaRepo, secretRepo, rows) = destroyingService(relay)

    relay.approveRemoval(rows("alice.removal"), aliceKeys)
    svc.syncDistributed()
    relay.approveRemoval(rows("charlie.removal"), charlieKeys)
    svc.syncDistributed()

    assertEquals(metaRepo.getAll(), Nil)
    assertEquals(secretRepo.getAll(), Nil)
  }

  test("The answer is deleted from the relay once it has been acted on, and not before") {
    val relay = CascadingFakeShareRelay()
    val (svc, _, _, rows) = destroyingService(relay)
    val removalId = rows("alice.removal").id

    svc.syncDistributed()
    assert(!relay.deletedRequestIds.contains(removalId), "a removal nobody answered was deleted")

    relay.approveRemoval(rows("alice.removal"), aliceKeys)
    svc.syncDistributed()
    assert(relay.deletedRequestIds.contains(removalId), "the answered removal was left on the relay")
  }

  test("A denied removal leaves the holder and the secret exactly where they were") {
    val relay = CascadingFakeShareRelay()
    val (svc, metaRepo, secretRepo, rows) = destroyingService(relay)

    relay.denyRemoval(rows("alice.removal"), aliceKeys)
    svc.syncDistributed()

    assertEquals(metaRepo.getAll().size, 2)
    assertEquals(secretRepo.getAll().map(_.state), List(SecretState.Destroying))
  }

  // A relay that could forge an approval could make this device forget a share that is still out there — the same
  // reason a retrieval approval is verified before its bytes are trusted.
  test("An approval signed by the wrong key is not an answer") {
    val relay = CascadingFakeShareRelay()
    val (svc, metaRepo, secretRepo, rows) = destroyingService(relay)

    relay.approveRemoval(rows("alice.removal"), charlieKeys)
    svc.syncDistributed()

    assertEquals(metaRepo.getAll().size, 2)
    assertEquals(secretRepo.getAll().map(_.state), List(SecretState.Destroying))
  }

  test("destroySecret flips the secret and asks every holder, before any of them has answered") {
    val relay = CascadingFakeShareRelay()
    val identityStore = InMemoryForgettableIdentityStore()
    val identity = IdentityService(identityStore)
    identity.register("owner")
    val contactRepo = FakeContactRepository(List(aliceContact, charlieContact))
    val metaRepo = FakeShareMetadataRepository()
    val secretRepo = FakeSecretRepository()
    secretRepo.save(
      Secret(secretId, "destroy test", MimeType.Default, 2, 2, secretCreatedAt, SecretState.Active)
    )
    List(aliceContact, charlieContact).foreach { contact =>
      metaRepo.save(ShareMetadata(id = UUID.randomUUID(), secretId = secretId, contactId = contact.id))
    }
    val svc = ShareService(
      relayResolver = FixedShareRelayResolver(relay),
      encryption = NoOpShareEncryption,
      shareRepository = FakeShareRepository(),
      shareMetadataRepository = metaRepo,
      secretRepository = secretRepo,
      contactRepository = contactRepo,
      contactManagement =
        ContactService(contactRepo, identityStore, InMemoryContactRelinkRepositoryForShareServiceTests()),
      keyConflictRepository = FakeKeyConflictRepository(),
      retainedDepositRepository = FakeRetainedDepositRepository(),
      identity = identity
    )

    svc.destroySecret(secretId)

    assertEquals(secretRepo.getAll().map(_.state), List(SecretState.Destroying))
    assertEquals(relay.openedRequests.count(_.transactionType == ShareTransactionType.Removal), 2)
    assertEquals(metaRepo.getAll().size, 2)
  }
