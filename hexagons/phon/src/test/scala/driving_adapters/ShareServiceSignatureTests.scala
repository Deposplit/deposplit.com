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
import driven_ports.ShareRelay
import driven_ports.ShareRepository
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import value_objects.svo.*

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** A keypair not tied to any `Identity` instance — used to sign fixture rows "as" a third party
  * (a known contact, or a stranger), independent of the `ShareService` under test's own identity.
  */
private final case class TestKeyPair(publicKey: Array[Byte], privateKey: Array[Byte]):
  def sign(bytes: Array[Byte]): Array[Byte] =
    val signer = Ed25519Signer()
    signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
    signer.update(bytes, 0, bytes.length)
    signer.generateSignature()

private object TestKeyPair:
  def generate(): TestKeyPair =
    val gen = Ed25519KeyPairGenerator()
    gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
    val pair = gen.generateKeyPair()
    TestKeyPair(
      pair.getPublic.asInstanceOf[Ed25519PublicKeyParameters].getEncoded,
      pair.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters].getEncoded
    )

/** A genuinely mutable in-memory store (not no-ops) — item 9's rotation-processing tests need to
  * observe the effect of `ContactService.updateContact` on the same contacts `ShareService` reads.
  */
private class FakeContactRepository(initial: List[Contact]) extends ContactRepository:
  private var contacts: List[Contact] = initial
  override def getAll(): List[Contact] = contacts
  override def getByEdKey(edPublicKey: Array[Byte]): Option[Contact] =
    contacts.find(_.edPublicKey.sameElements(edPublicKey))
  override def getById(id: UUID): Option[Contact] = contacts.find(_.id == id)
  override def save(contact: Contact): Unit = contacts = contact :: contacts.filterNot(_.id == contact.id)
  override def delete(contactId: UUID): Unit = contacts = contacts.filterNot(_.id == contactId)

private class FakeShareRepository extends ShareRepository:
  private var shares: List[HeldShare] = Nil
  override def getAll(): List[HeldShare] = shares
  override def getPlaintextShare(secretId: UUID): Option[Array[Byte]] = shares.find(_.secretId == secretId).map(_.plaintextShare)
  override def save(share: HeldShare): Unit = shares = share :: shares
  override def delete(shareId: UUID): Unit = shares = shares.filterNot(_.id == shareId)

private class FakeShareMetadataRepository extends ShareMetadataRepository:
  private var metas: List[ShareMetadata] = Nil
  override def getAll(): List[ShareMetadata] = metas
  override def save(share: ShareMetadata): Unit = metas = share :: metas
  override def delete(shareId: UUID): Unit = metas = metas.filterNot(_.id == shareId)

private class FakeSecretRepository extends SecretRepository:
  private var secrets: List[Secret] = Nil
  override def getAll(): List[Secret] = secrets
  override def save(secret: Secret): Unit = secrets = secret :: secrets.filterNot(_.id == secret.id)
  override def delete(secretId: UUID): Unit = secrets = secrets.filterNot(_.id == secretId)

private object NoOpShareEncryption extends ShareEncryption:
  override def encrypt(plaintext: Array[Byte], recipientXPublicKey: Array[Byte]): Array[Byte] = plaintext
  override def decrypt(noncePlusCiphertext: Array[Byte], recipientXPublicKey: Array[Byte]): Array[Byte] = noncePlusCiphertext

/** In-memory ShareRelay test double — `listShareRequests` ignores its filters and just returns
  * whatever `pending` is configured to, which is all these tests need.
  */
private class FakeShareRelay(var unreachable: Boolean = false) extends ShareRelay:
  case class OpenedRequest(secretId: UUID, recipientKey: Array[Byte], transactionType: ShareTransactionType, k: Option[Int], n: Option[Int])

  var pending: List[ShareRequest] = Nil
  var byId: Map[UUID, ShareRequest] = Map.empty
  var respondCalls: List[UUID] = Nil
  var deletedRequestIds: List[UUID] = Nil
  var openedRequests: List[OpenedRequest] = Nil

  // Item 9
  case class WithdrawCall(senderKey: Option[Array[Byte]], secretId: Option[UUID])
  case class PushedRotation(recipientKey: Array[Byte], newEd25519Key: Array[Byte], newX25519Key: Array[Byte], signature: Array[Byte])
  var withdrawCalls: List[WithdrawCall] = Nil
  var pushedRotations: List[PushedRotation] = Nil
  var rotationsToReturn: List[KeyRotation] = Nil
  var deletedRotationIds: List[UUID] = Nil
  var throwOnWithdraw: Boolean = false

  override def openShareRequest(
      secretId: UUID,
      recipientKey: Array[Byte],
      label: String,
      secretCreatedAt: Instant,
      transactionType: ShareTransactionType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]],
      k: Option[Int],
      n: Option[Int],
      senderSignature: Array[Byte]
  ): ShareRequest =
    openedRequests :+= OpenedRequest(secretId, recipientKey, transactionType, k, n)
    val selfApproved = transactionType == ShareTransactionType.Inventory
    val now = Instant.now()
    ShareRequest(
      id = UUID.randomUUID(),
      secretId = secretId,
      senderKey = Array.emptyByteArray,
      recipientKey = recipientKey,
      label = label,
      secretCreatedAt = secretCreatedAt,
      transactionType = transactionType,
      state = if selfApproved then ShareRequestState.Approved else ShareRequestState.Pending,
      shareId = shareId,
      requestedAt = now,
      respondedAt = if selfApproved then Some(now) else None,
      ciphertext = None,
      k = k,
      n = n,
      senderSignature = senderSignature,
      recipientSignature = None
    )

  override def listShareRequests(
      role: Role,
      transactionType: Option[ShareTransactionType],
      state: Option[ShareRequestState]
  ): List[ShareRequest] =
    if unreachable then throw RuntimeException("simulated relay outage")
    else pending.filter(r => transactionType.forall(_ == r.transactionType) && state.forall(_ == r.state))

  override def getShareRequest(requestId: UUID): ShareRequest = byId(requestId)

  override def respondToShareRequest(
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]],
      recipientSignature: Array[Byte]
  ): ShareRequest =
    respondCalls :+= requestId
    val updated = byId(requestId).copy(state = if approved then ShareRequestState.Approved else ShareRequestState.Denied)
    byId += requestId -> updated
    updated

  override def deleteShareRequest(requestId: UUID): Unit = deletedRequestIds :+= requestId
  override def deleteShareRequests(senderKey: Option[Array[Byte]], secretId: Option[UUID]): Unit = ()

  override def withdrawShareRequests(senderKey: Option[Array[Byte]] = None, secretId: Option[UUID] = None): Unit =
    withdrawCalls :+= WithdrawCall(senderKey, secretId)
    if throwOnWithdraw then throw RuntimeException("simulated withdraw failure")

  override def pushRotation(recipientKey: Array[Byte], newEd25519Key: Array[Byte], newX25519Key: Array[Byte], signature: Array[Byte]): Unit =
    pushedRotations :+= PushedRotation(recipientKey, newEd25519Key, newX25519Key, signature)

  override def listRotations(): List[KeyRotation] =
    if unreachable then throw RuntimeException("simulated relay outage")
    else rotationsToReturn

  override def deleteRotation(id: UUID): Unit = deletedRotationIds :+= id

/** Resolves to the same relay regardless of the requested URL — these tests exercise signature
  * verification, not multi-relay routing (see `ShareRelayResolverFanOutTests` for that).
  */
private class FixedShareRelayResolver(relay: ShareRelay) extends driven_ports.ShareRelayResolver:
  override def resolve(relayBaseUrl: Option[String]): ShareRelay = relay

/** Covers the recipient-side signature-verification gating described in `deposplit.com/CLAUDE.md`'s
  * BYOR section: `syncInbox`/`listPendingRequests` must drop rows with an unverifiable
  * `senderSignature` (unknown sender, or a genuine contact's key but a forged/mismatched
  * signature) instead of trusting whatever the relay returns, and `respond` must reject explicitly.
  */
class ShareServiceSignatureTests extends munit.FunSuite:

  private val aliceKeys = TestKeyPair.generate()
  private val strangerKeys = TestKeyPair.generate()

  private val aliceContact = Contact(
    id = UUID.randomUUID(),
    pseudonym = "alice",
    edPublicKey = aliceKeys.publicKey,
    xPublicKey = Array.fill(32)(0x01.toByte),
    verificationLevel = VerificationLevel.VeryHigh,
    verifiedAt = None,
    addedAt = Instant.now()
  )

  private def newService(
      relay: FakeShareRelay,
      contacts: List[Contact] = List(aliceContact)
  ): (ShareService, IdentityService, FakeShareRepository, FakeContactRepository, FakeShareMetadataRepository) =
    val bobIdentity = IdentityService(InMemoryForgettableIdentityStore())
    bobIdentity.register("bob")
    val shareRepo = FakeShareRepository()
    val contactRepo = FakeContactRepository(contacts)
    val metaRepo = FakeShareMetadataRepository()
    val svc = ShareService(
      relayResolver = FixedShareRelayResolver(relay),
      encryption = NoOpShareEncryption,
      shareRepository = shareRepo,
      shareMetadataRepository = metaRepo,
      secretRepository = FakeSecretRepository(),
      contactRepository = contactRepo,
      contactManagement = ContactService(contactRepo),
      identity = bobIdentity
    )
    (svc, bobIdentity, shareRepo, contactRepo, metaRepo)

  private def depositRow(
      id: UUID,
      senderKey: Array[Byte],
      recipientKey: Array[Byte],
      senderSignature: Array[Byte],
      ciphertext: Array[Byte] = Array[Byte](1, 2, 3)
  ): ShareRequest =
    ShareRequest(
      id = id,
      secretId = UUID.randomUUID(),
      senderKey = senderKey,
      recipientKey = recipientKey,
      label = "test secret",
      secretCreatedAt = Instant.now(),
      transactionType = ShareTransactionType.Deposit,
      state = ShareRequestState.Pending,
      shareId = None,
      requestedAt = Instant.now(),
      respondedAt = None,
      ciphertext = Some(ciphertext),
      k = Some(2),
      n = Some(3),
      senderSignature = senderSignature,
      recipientSignature = None
    )

  private def signOpenAs(signer: TestKeyPair, row: ShareRequest): Array[Byte] =
    signer.sign(
      PayloadCanonical.forOpen(row.secretId, row.transactionType, row.recipientKey, row.label, row.secretCreatedAt, row.shareId, row.ciphertext, row.k, row.n)
    )

  test("syncInbox approves and saves a Deposit with a valid senderSignature from a known contact") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, aliceKeys.publicKey, bob.edPublicKey(), Array.empty)
    val row = unsigned.copy(senderSignature = signOpenAs(aliceKeys, unsigned))
    relay.pending = List(row)
    relay.byId = Map(id -> row)

    svc.syncInbox()

    assertEquals(relay.respondCalls, List(id))
    assertEquals(shareRepo.getAll().map(_.id), List(id))
  }

  test("syncInbox skips a Deposit whose senderSignature doesn't verify against the claimed sender") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, aliceKeys.publicKey, bob.edPublicKey(), Array.empty)
    // Signed by a stranger, not by alice — claims to be from alice but doesn't verify against her key.
    val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.pending = List(forged)
    relay.byId = Map(id -> forged)

    svc.syncInbox()

    assert(relay.respondCalls.isEmpty)
    assert(shareRepo.getAll().isEmpty)
  }

  test("syncInbox skips a Deposit from an unknown sender even with a self-consistent signature") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, strangerKeys.publicKey, bob.edPublicKey(), Array.empty)
    val row = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.pending = List(row)
    relay.byId = Map(id -> row)

    svc.syncInbox()

    assert(relay.respondCalls.isEmpty)
    assert(shareRepo.getAll().isEmpty)
  }

  test("listPendingRequests filters out a row with an unverifiable senderSignature") {
    val relay = FakeShareRelay()
    val (svc, bob, _, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, aliceKeys.publicKey, bob.edPublicKey(), Array.empty)
      .copy(transactionType = ShareTransactionType.Removal)
    val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.pending = List(forged)

    assertEquals(svc.listPendingRequests(), List.empty)
  }

  test("respond throws SignatureVerificationException when senderSignature doesn't verify") {
    val relay = FakeShareRelay()
    val (svc, bob, _, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, aliceKeys.publicKey, bob.edPublicKey(), Array.empty)
      .copy(transactionType = ShareTransactionType.Removal)
    val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.byId = Map(id -> forged)

    intercept[SignatureVerificationException] {
      svc.respond(id, approved = true)
    }
  }

  // ── Identity recovery (item 8) ──────────────────────────────────────────────

  private def newServiceForRecoveryTest(relay: FakeShareRelay): (ShareService, IdentityService, FakeShareRepository, FakeSecretRepository, FakeShareMetadataRepository) =
    val bobIdentity = IdentityService(InMemoryForgettableIdentityStore())
    bobIdentity.register("bob")
    val shareRepo = FakeShareRepository()
    val secretRepo = FakeSecretRepository()
    val metaRepo = FakeShareMetadataRepository()
    val contactRepo = FakeContactRepository(List(aliceContact))
    val svc = ShareService(
      relayResolver = FixedShareRelayResolver(relay),
      encryption = NoOpShareEncryption,
      shareRepository = shareRepo,
      shareMetadataRepository = metaRepo,
      secretRepository = secretRepo,
      contactRepository = contactRepo,
      contactManagement = ContactService(contactRepo),
      identity = bobIdentity
    )
    (svc, bobIdentity, shareRepo, secretRepo, metaRepo)

  /** A self-approved recoveryMetadata row, as the relay would hand it back — Approved state and
    * respondedAt set at creation, since this type has no consent phase (see item 8).
    */
  private def approvedRecoveryMetadataRow(
      secretId: UUID,
      senderKey: Array[Byte],
      recipientKey: Array[Byte],
      signer: TestKeyPair,
      k: Int = 2,
      n: Int = 3,
      label: String = "recovered secret"
  ): ShareRequest =
    val createdAt = Instant.now()
    val canon = PayloadCanonical.forOpen(secretId, ShareTransactionType.Inventory, recipientKey, label, createdAt, None, None, Some(k), Some(n))
    val sig = signer.sign(canon)
    val now = Instant.now()
    ShareRequest(
      id = UUID.randomUUID(),
      secretId = secretId,
      senderKey = senderKey,
      recipientKey = recipientKey,
      label = label,
      secretCreatedAt = createdAt,
      transactionType = ShareTransactionType.Inventory,
      state = ShareRequestState.Approved,
      shareId = None,
      requestedAt = now,
      respondedAt = Some(now),
      ciphertext = None,
      k = Some(k),
      n = Some(n),
      senderSignature = sig,
      recipientSignature = None
    )

  test("pushRecoveryMetadata opens a recoveryMetadata push for every HeldShare from that contact") {
    val relay = FakeShareRelay()
    val (svc, _, shareRepo, _, _) = newServiceForRecoveryTest(relay)
    val secretId = UUID.randomUUID()
    shareRepo.save(
      HeldShare(
        id = UUID.randomUUID(),
        secretId = secretId,
        label = "test secret",
        contactId = aliceContact.id,
        createdAt = Instant.now(),
        pickedUpAt = Instant.now(),
        plaintextShare = Array[Byte](9),
        k = 2,
        n = 3
      )
    )

    svc.pushRecoveryMetadata(aliceContact.id)

    assertEquals(relay.openedRequests.size, 1)
    val opened = relay.openedRequests.head
    assertEquals(opened.transactionType, ShareTransactionType.Inventory)
    assertEquals(opened.secretId, secretId)
    assert(opened.recipientKey.sameElements(aliceContact.edPublicKey))
    assertEquals(opened.k, Some(2))
    assertEquals(opened.n, Some(3))
  }

  test("pushRecoveryMetadata throws for an unknown contact") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, _) = newServiceForRecoveryTest(relay)

    intercept[IllegalStateException] {
      svc.pushRecoveryMetadata(UUID.randomUUID())
    }
  }

  test("syncInbox processes an approved recoveryMetadata push and rebuilds Secret and ShareMetadata") {
    val relay = FakeShareRelay()
    val (svc, bob, _, secretRepo, metaRepo) = newServiceForRecoveryTest(relay)
    val secretId = UUID.randomUUID()
    val pushRow = approvedRecoveryMetadataRow(secretId, aliceKeys.publicKey, bob.edPublicKey(), aliceKeys)
    relay.pending = List(pushRow)

    svc.syncInbox()

    val secrets = secretRepo.getAll()
    assertEquals(secrets.map(_.id), List(secretId))
    assertEquals(secrets.head.k, 2)
    assertEquals(secrets.head.n, 3)
    val metas = metaRepo.getAll()
    assertEquals(metas.size, 1)
    assertEquals(metas.head.secretId, secretId)
    assertEquals(metas.head.contactId, aliceContact.id)
    assertEquals(relay.deletedRequestIds, List(pushRow.id))
  }

  test("syncInbox ignores a recoveryMetadata push with a forged signature") {
    val relay = FakeShareRelay()
    val (svc, bob, _, secretRepo, metaRepo) = newServiceForRecoveryTest(relay)
    val secretId = UUID.randomUUID()
    // Claims to be from alice but signed by a stranger.
    val pushRow = approvedRecoveryMetadataRow(secretId, aliceKeys.publicKey, bob.edPublicKey(), strangerKeys)
    relay.pending = List(pushRow)

    svc.syncInbox()

    assert(secretRepo.getAll().isEmpty)
    assert(metaRepo.getAll().isEmpty)
    assert(relay.deletedRequestIds.isEmpty)
  }

  // ── Item 9: rotation push (client primitive + receive-side) and withdraw tombstone ───────────

  /** Builds a signed KeyRotation notice — the signing counterpart of `relay.pushRotation`.
    * `signer` is the party whose signature is attached; pass something other than the keypair
    * backing `oldEd25519Key` to build a forged notice.
    */
  private def signedRotation(
      oldEd25519Key: Array[Byte],
      recipientKey: Array[Byte],
      signer: TestKeyPair,
      newEd25519Key: Array[Byte] = Array.fill(32)(0x0a.toByte),
      newX25519Key: Array[Byte] = Array.fill(32)(0x0b.toByte)
  ): KeyRotation =
    val canon = PayloadCanonical.forRotation(recipientKey, newEd25519Key, newX25519Key)
    val sig = signer.sign(canon)
    KeyRotation(UUID.randomUUID(), oldEd25519Key, recipientKey, newEd25519Key, newX25519Key, sig, Instant.now())

  test("pushRotation signs with the current identity and pushes to the contact's relay") {
    val relay = FakeShareRelay()
    val (svc, bob, _, _, _) = newService(relay)
    val newEd = Array.fill(32)(0x08.toByte)
    val newX = Array.fill(32)(0x09.toByte)

    svc.pushRotation(aliceContact.id, newEd, newX)

    assertEquals(relay.pushedRotations.size, 1)
    val pushed = relay.pushedRotations.head
    assert(pushed.recipientKey.sameElements(aliceContact.edPublicKey))
    assert(pushed.newEd25519Key.sameElements(newEd))
    assert(pushed.newX25519Key.sameElements(newX))
    val canon = PayloadCanonical.forRotation(aliceContact.edPublicKey, newEd, newX)
    assert(bob.verify(canon, pushed.signature, bob.edPublicKey()))
  }

  test("pushRotation throws for an unknown contact") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, _) = newService(relay)

    intercept[IllegalStateException] {
      svc.pushRotation(UUID.randomUUID(), Array.fill(32)(0x01.toByte), Array.fill(32)(0x02.toByte))
    }
  }

  test("syncInbox auto-accepts a valid rotation notice and downgrades verification level to Low") {
    val relay = FakeShareRelay()
    // aliceContact starts at VeryHigh.
    val (svc, bob, _, contactRepo, _) = newService(relay)
    val newEd = Array.fill(32)(0x0c.toByte)
    val newX = Array.fill(32)(0x0d.toByte)
    val notice = signedRotation(aliceKeys.publicKey, bob.edPublicKey(), aliceKeys, newEd, newX)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    val updated = contactRepo.getById(aliceContact.id).getOrElse(fail("contact missing"))
    assertEquals(updated.id, aliceContact.id) // updated in place, contactId preserved
    assert(updated.edPublicKey.sameElements(newEd))
    assert(updated.xPublicKey.sameElements(newX))
    assertEquals(updated.verificationLevel, VerificationLevel.Low)
    assertEquals(relay.deletedRotationIds, List(notice.id))
  }

  test("syncInbox never raises verification level above Low even from an already-lower level") {
    val relay = FakeShareRelay()
    val daveKeys = TestKeyPair.generate()
    val daveContact = aliceContact.copy(
      id = UUID.randomUUID(),
      pseudonym = "dave",
      edPublicKey = daveKeys.publicKey,
      verificationLevel = VerificationLevel.VeryLow
    )
    val (svc, bob, _, contactRepo, _) = newService(relay, contacts = List(daveContact))
    val notice = signedRotation(daveKeys.publicKey, bob.edPublicKey(), daveKeys)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    // Continuity of key control is not a fresh personhood check (item 10) — it can never raise
    // the level, only cap it at Low.
    assertEquals(contactRepo.getById(daveContact.id).map(_.verificationLevel), Some(VerificationLevel.VeryLow))
  }

  test("syncInbox ignores a rotation notice with a forged signature") {
    val relay = FakeShareRelay()
    val (svc, bob, _, contactRepo, _) = newService(relay)
    // Claims to be from alice (oldEd25519Key = aliceKeys.publicKey) but signed by a stranger.
    val notice = signedRotation(aliceKeys.publicKey, bob.edPublicKey(), strangerKeys)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    assert(contactRepo.getById(aliceContact.id).exists(_.edPublicKey.sameElements(aliceContact.edPublicKey)))
    assert(relay.deletedRotationIds.isEmpty)
  }

  test("syncInbox ignores a rotation notice from an unknown old key") {
    val relay = FakeShareRelay()
    val (svc, bob, _, contactRepo, _) = newService(relay)
    val notice = signedRotation(strangerKeys.publicKey, bob.edPublicKey(), strangerKeys)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    assertEquals(contactRepo.getAll(), List(aliceContact))
    assert(relay.deletedRotationIds.isEmpty)
  }

  test("deleteHeldShare withdraws from the sender's relay scoped by secretId then deletes locally") {
    val relay = FakeShareRelay()
    val (svc, _, shareRepo, _, _) = newService(relay)
    val secretId = UUID.randomUUID()
    val shareId = UUID.randomUUID()
    shareRepo.save(
      HeldShare(
        id = shareId,
        secretId = secretId,
        label = "x",
        contactId = aliceContact.id,
        createdAt = Instant.now(),
        pickedUpAt = Instant.now(),
        plaintextShare = Array[Byte](1),
        k = 2,
        n = 3
      )
    )

    svc.deleteHeldShare(shareId)

    assertEquals(relay.withdrawCalls, List(relay.WithdrawCall(senderKey = None, secretId = Some(secretId))))
    assert(shareRepo.getAll().isEmpty)
  }

  test("deleteAllHeldFromSender withdraws by senderKey then deletes all locally") {
    val relay = FakeShareRelay()
    val (svc, _, shareRepo, _, _) = newService(relay)
    shareRepo.save(
      HeldShare(
        id = UUID.randomUUID(),
        secretId = UUID.randomUUID(),
        label = "x",
        contactId = aliceContact.id,
        createdAt = Instant.now(),
        pickedUpAt = Instant.now(),
        plaintextShare = Array[Byte](1),
        k = 2,
        n = 3
      )
    )
    shareRepo.save(
      HeldShare(
        id = UUID.randomUUID(),
        secretId = UUID.randomUUID(),
        label = "y",
        contactId = aliceContact.id,
        createdAt = Instant.now(),
        pickedUpAt = Instant.now(),
        plaintextShare = Array[Byte](2),
        k = 2,
        n = 3
      )
    )

    svc.deleteAllHeldFromSender(aliceContact.id)

    assertEquals(relay.withdrawCalls.size, 1)
    assert(relay.withdrawCalls.head.senderKey.exists(_.sameElements(aliceContact.edPublicKey)))
    assertEquals(relay.withdrawCalls.head.secretId, None)
    assert(shareRepo.getAll().isEmpty)
  }

  test("deleteHeldShare still deletes locally even if the withdraw call fails") {
    val relay = FakeShareRelay()
    relay.throwOnWithdraw = true
    val (svc, _, shareRepo, _, _) = newService(relay)
    val shareId = UUID.randomUUID()
    shareRepo.save(
      HeldShare(
        id = shareId,
        secretId = UUID.randomUUID(),
        label = "x",
        contactId = aliceContact.id,
        createdAt = Instant.now(),
        pickedUpAt = Instant.now(),
        plaintextShare = Array[Byte](1),
        k = 2,
        n = 3
      )
    )

    svc.deleteHeldShare(shareId)

    assert(shareRepo.getAll().isEmpty)
  }

  /** A bare deposit row shaped only for `syncDistributed()`'s purposes — that method never
    * checks signatures, so `senderSignature` is deliberately empty filler, not a genuine one.
    */
  private def bareDepositRow(id: UUID, secretId: UUID, recipientKey: Array[Byte], state: ShareRequestState): ShareRequest =
    ShareRequest(
      id = id,
      secretId = secretId,
      senderKey = Array.fill(32)(0x05.toByte),
      recipientKey = recipientKey,
      label = "test secret",
      secretCreatedAt = Instant.now(),
      transactionType = ShareTransactionType.Deposit,
      state = state,
      shareId = None,
      requestedAt = Instant.now(),
      respondedAt = if state == ShareRequestState.Pending then None else Some(Instant.now()),
      ciphertext = None,
      k = Some(2),
      n = Some(3),
      senderSignature = Array.emptyByteArray,
      recipientSignature = None
    )

  test("syncDistributed removes the local pointer and deletes the relay row for a withdrawn deposit") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, metaRepo) = newService(relay)
    val depositId = UUID.randomUUID()
    val secretId = UUID.randomUUID()
    metaRepo.save(ShareMetadata(depositId, secretId, aliceContact.id))
    relay.pending = List(bareDepositRow(depositId, secretId, aliceContact.edPublicKey, ShareRequestState.Withdrawn))

    svc.syncDistributed()

    assert(metaRepo.getAll().isEmpty)
    assertEquals(relay.deletedRequestIds, List(depositId))
  }

  test("syncDistributed still upserts normally for a non-withdrawn row") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, metaRepo) = newService(relay)
    val depositId = UUID.randomUUID()
    val secretId = UUID.randomUUID()
    relay.pending = List(bareDepositRow(depositId, secretId, aliceContact.edPublicKey, ShareRequestState.Approved))

    svc.syncDistributed()

    assertEquals(metaRepo.getAll().map(_.id), List(depositId))
    assert(relay.deletedRequestIds.isEmpty)
  }
