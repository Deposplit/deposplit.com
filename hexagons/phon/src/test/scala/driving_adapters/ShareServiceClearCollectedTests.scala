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

/** `reconstruct` is a pure read, so the copies it collects outlive it. `clearCollectedShares` is the other half: it
  * takes them back off the relay without tearing the secret down, which is what lets the retrieval flow be run a second
  * time — before it existed, an approved row counted as live forever and `requestAll` would never ask again.
  */
class ShareServiceClearCollectedTests extends munit.FunSuite:

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
  private val otherSecretId = UUID.randomUUID()
  private val secretCreatedAt = Instant.now()

  private def retrievalRow(
      forSecret: UUID,
      holderKey: Array[Byte],
      state: ShareRequestState,
      ciphertext: Option[Array[Byte]]
  ): ShareRequest =
    ShareRequest(
      id = UUID.randomUUID(),
      secretId = forSecret,
      senderKey = Array.emptyByteArray,
      recipientKey = holderKey,
      label = "clearing test",
      secretCreatedAt = secretCreatedAt,
      transactionType = ShareTransactionType.Retrieval,
      state = state,
      requestedAt = Instant.now(),
      respondedAt = None,
      ciphertext = ciphertext,
      k = None,
      n = None,
      mimeType = None,
      senderSignature = Array.emptyByteArray,
      recipientSignature = None
    )

  private def depositRow(holderKey: Array[Byte]): ShareRequest =
    retrievalRow(secretId, holderKey, ShareRequestState.Approved, Some(Array[Byte](7, 7, 7)))
      .copy(transactionType = ShareTransactionType.Deposit)

  /** Wires a sender-side service holding one 2-of-2 secret split between alice and charlie. */
  private def newSenderService(
      relay: FakeShareRelay
  ): (ShareService, FakeShareMetadataRepository, FakeSecretRepository) =
    val identityStore = InMemoryForgettableIdentityStore()
    val identity = IdentityService(identityStore)
    identity.register("owner")
    val contactRepo = FakeContactRepository(List(aliceContact, charlieContact))
    val metaRepo = FakeShareMetadataRepository()
    val secretRepo = FakeSecretRepository()
    secretRepo.save(
      Secret(
        id = secretId,
        label = "clearing test",
        mimeType = MimeType.Default,
        k = 2,
        n = 2,
        secretCreatedAt = secretCreatedAt,
        state = SecretState.Active
      )
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
    (svc, metaRepo, secretRepo)

  /** An ask nobody has answered yet is part of the same flow as a copy already collected. Leaving it standing would let
    * shares go on arriving after the owner said they were finished with the secret.
    */
  test("clearing takes back both the collected copies and the asks still waiting") {
    val relay = FakeShareRelay()
    val (svc, _, _) = newSenderService(relay)

    val collectedFromAlice =
      retrievalRow(secretId, aliceKeys.publicKey, ShareRequestState.Approved, Some(Array[Byte](1)))
    val stillAskingCharlie = retrievalRow(secretId, charlieKeys.publicKey, ShareRequestState.Pending, None)
    relay.pending = List(collectedFromAlice, stillAskingCharlie)

    svc.clearCollectedShares(secretId)

    assertEquals(relay.deletedRequestIds.toSet, Set(collectedFromAlice.id, stillAskingCharlie.id))
  }

  /** Clearing is not teardown: the holders keep their shares, the deposit rows that record them stay, and so does every
    * local record of the split. Only this owner's collected copies go.
    */
  test("clearing leaves the deposits, the other secrets and every local record alone") {
    val relay = FakeShareRelay()
    val (svc, metaRepo, secretRepo) = newSenderService(relay)

    val collectedFromAlice =
      retrievalRow(secretId, aliceKeys.publicKey, ShareRequestState.Approved, Some(Array[Byte](1)))
    val anotherSecretsCopy =
      retrievalRow(otherSecretId, aliceKeys.publicKey, ShareRequestState.Approved, Some(Array[Byte](2)))
    val alicesDeposit = depositRow(aliceKeys.publicKey)
    relay.pending = List(collectedFromAlice, anotherSecretsCopy, alicesDeposit)

    svc.clearCollectedShares(secretId)

    assertEquals(relay.deletedRequestIds, List(collectedFromAlice.id))
    assertEquals(metaRepo.getAll().count(_.secretId == secretId), 2)
    assertEquals(secretRepo.getAll().map(_.id), List(secretId))
  }

  /** The point of the whole feature. An `Approved` row counts as a live request, so until it is cleared `requestAll`
    * skips every holder and the retrieval flow cannot be run a second time without editing the relay by hand.
    */
  test("a secret whose copies have been cleared can have its shares retrieved again") {
    val relay = FakeShareRelay()
    val (svc, _, _) = newSenderService(relay)

    val collectedFromAlice =
      retrievalRow(secretId, aliceKeys.publicKey, ShareRequestState.Approved, Some(Array[Byte](1)))
    val collectedFromCharlie =
      retrievalRow(secretId, charlieKeys.publicKey, ShareRequestState.Approved, Some(Array[Byte](2)))
    relay.pending = List(collectedFromAlice, collectedFromCharlie)

    svc.requestAll(secretId)
    assertEquals(relay.openedRequests, Nil, "both holders still have a live row, so nobody should be asked again")

    svc.clearCollectedShares(secretId)
    relay.pending = relay.pending.filterNot(r => relay.deletedRequestIds.contains(r.id))

    svc.requestAll(secretId)

    assertEquals(relay.openedRequests.map(_.transactionType).distinct, List(ShareTransactionType.Retrieval))
    assertEquals(
      relay.openedRequests.map(_.recipientKey.toSeq).toSet,
      Set(aliceKeys.publicKey.toSeq, charlieKeys.publicKey.toSeq)
    )
  }
