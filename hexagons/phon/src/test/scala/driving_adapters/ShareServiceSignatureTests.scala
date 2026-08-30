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
import driven_ports.KeyConflictRepository
import driven_ports.RetainedDepositRepository
import driven_ports.SecretRepository
import driven_ports.ShareMetadataRepository
import driven_ports.ShareRelay
import driven_ports.ShareRepository
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import shamir.SecretSharing
import value_objects.svo.*

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** A keypair not tied to any `Identity` instance — used to sign fixture rows "as" a third party (a known contact, or a
  * stranger), independent of the `ShareService` under test's own identity.
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

/** A genuinely mutable in-memory store (not no-ops) — item 9's rotation-processing tests need to observe the effect of
  * `ContactService.updateContact` on the same contacts `ShareService` reads.
  */
private class FakeContactRepository(initial: List[Contact]) extends ContactRepository:
  private var contacts: List[Contact] = initial
  override def getAll(): List[Contact] = contacts
  override def getByVerifyKey(verifyKey: Array[Byte]): Option[Contact] =
    contacts.find(_.verifyKey.sameElements(verifyKey))
  override def getById(id: UUID): Option[Contact] = contacts.find(_.id == id)
  override def save(contact: Contact): Unit = contacts = contact :: contacts.filterNot(_.id == contact.id)
  override def delete(contactId: UUID): Unit = contacts = contacts.filterNot(_.id == contactId)

private class FakeShareRepository extends ShareRepository:
  private var shares: List[HeldShare] = Nil
  override def getAll(): List[HeldShare] = shares
  override def getPlaintextShare(secretId: UUID): Option[Array[Byte]] =
    shares.find(_.secretId == secretId).map(_.plaintextShare)
  override def save(share: HeldShare): Unit = shares = share :: shares
  override def delete(shareId: UUID): Unit = shares = shares.filterNot(_.id == shareId)

private class FakeShareMetadataRepository extends ShareMetadataRepository:
  private var metas: List[ShareMetadata] = Nil
  override def getAll(): List[ShareMetadata] = metas
  override def save(share: ShareMetadata): Unit = metas = share :: metas.filterNot(_.id == share.id)
  override def delete(shareId: UUID): Unit = metas = metas.filterNot(_.id == shareId)

private class FakeRetainedDepositRepository extends RetainedDepositRepository:
  private var blobs: List[RetainedDepositBlob] = Nil
  override def getAll(): List[RetainedDepositBlob] = blobs
  override def save(blob: RetainedDepositBlob): Unit = blobs = blob :: blobs.filterNot(_.id == blob.id)
  override def delete(id: UUID): Unit = blobs = blobs.filterNot(_.id == id)

private class FakeSecretRepository extends SecretRepository:
  private var secrets: List[Secret] = Nil
  override def getAll(): List[Secret] = secrets
  override def save(secret: Secret): Unit = secrets = secret :: secrets.filterNot(_.id == secret.id)
  override def delete(secretId: UUID): Unit = secrets = secrets.filterNot(_.id == secretId)

private class FakeKeyConflictRepository extends KeyConflictRepository:
  private var conflicts: List[KeyConflict] = Nil
  override def getAll(): List[KeyConflict] = conflicts
  override def save(conflict: KeyConflict): Unit = conflicts = conflict :: conflicts
  override def delete(id: UUID): Unit = conflicts = conflicts.filterNot(_.id == id)

private object NoOpShareEncryption extends ShareEncryption:
  override def encrypt(plaintext: Array[Byte], recipientEncKey: Array[Byte]): Array[Byte] = plaintext
  override def decrypt(noncePlusCiphertext: Array[Byte], recipientEncKey: Array[Byte]): Array[Byte] =
    noncePlusCiphertext

/** In-memory ShareRelay test double — `listShareRequests` ignores its filters and just returns whatever `pending` is
  * configured to, which is all these tests need.
  */
private class FakeShareRelay(var unreachable: Boolean = false) extends ShareRelay:
  case class OpenedRequest(
      secretId: UUID,
      recipientKey: Array[Byte],
      transactionType: ShareTransactionType,
      k: Option[Int],
      n: Option[Int]
  )

  var pending: List[ShareRequest] = Nil
  var byId: Map[UUID, ShareRequest] = Map.empty
  var respondCalls: List[UUID] = Nil
  var deletedRequestIds: List[UUID] = Nil
  var openedRequests: List[OpenedRequest] = Nil

  // Item 9
  case class WithdrawCall(senderKey: Option[Array[Byte]], secretId: Option[UUID])
  case class PushedRotation(
      recipientKey: Array[Byte],
      newVerifyKey: Array[Byte],
      newEncKey: Array[Byte],
      newCipherSuite: CipherSuite,
      signature: Array[Byte]
  )
  var withdrawCalls: List[WithdrawCall] = Nil
  var pushedRotations: List[PushedRotation] = Nil
  var rotationsToReturn: List[KeyRotation] = Nil
  var deletedRotationIds: List[UUID] = Nil
  var throwOnWithdraw: Boolean = false
  var throwOnPushRotation: Boolean = false

  // Item 12
  case class PushedHeartbeat(ownerKey: Array[Byte], secretIds: Seq[UUID], optedOut: Boolean, signature: Array[Byte])
  var pushedHeartbeats: List[PushedHeartbeat] = Nil
  var heartbeatsToReturn: List[CustodyHeartbeat] = Nil
  var throwOnPushHeartbeat: Boolean = false

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
    val updated = byId(requestId).copy(
      state = if approved then ShareRequestState.Approved else ShareRequestState.Denied,
      recipientSignature = Some(recipientSignature)
    )
    byId += requestId -> updated
    updated

  override def deleteShareRequest(requestId: UUID): Unit = deletedRequestIds :+= requestId
  override def deleteShareRequests(senderKey: Option[Array[Byte]], secretId: Option[UUID]): Unit = ()

  override def withdrawShareRequests(senderKey: Option[Array[Byte]] = None, secretId: Option[UUID] = None): Unit =
    withdrawCalls :+= WithdrawCall(senderKey, secretId)
    if throwOnWithdraw then throw RuntimeException("simulated withdraw failure")

  override def pushRotation(
      recipientKey: Array[Byte],
      newVerifyKey: Array[Byte],
      newEncKey: Array[Byte],
      newCipherSuite: CipherSuite,
      signature: Array[Byte]
  ): Unit =
    if throwOnPushRotation then throw RuntimeException("simulated push failure")
    pushedRotations :+= PushedRotation(recipientKey, newVerifyKey, newEncKey, newCipherSuite, signature)

  override def listRotations(): List[KeyRotation] =
    if unreachable then throw RuntimeException("simulated relay outage")
    else rotationsToReturn

  override def deleteRotation(id: UUID): Unit = deletedRotationIds :+= id

  override def pushHeartbeat(
      ownerKey: Array[Byte],
      secretIds: Seq[UUID],
      optedOut: Boolean,
      signature: Array[Byte]
  ): Unit =
    pushedHeartbeats :+= PushedHeartbeat(ownerKey, secretIds, optedOut, signature)
    if throwOnPushHeartbeat then throw RuntimeException("simulated push-heartbeat failure")

  override def listHeartbeats(): List[CustodyHeartbeat] =
    if unreachable then throw RuntimeException("simulated relay outage")
    else heartbeatsToReturn

/** Resolves to the same relay regardless of the requested URL — these tests exercise signature verification, not
  * multi-relay routing (see `ShareRelayResolverFanOutTests` for that).
  */
private class FixedShareRelayResolver(relay: ShareRelay) extends driven_ports.ShareRelayResolver:
  override def resolve(relayBaseUrl: Option[String]): ShareRelay = relay

/** Covers the recipient-side signature-verification gating described in `deposplit.com/CLAUDE.md`'s BYOR section:
  * `syncInbox`/`listPendingRequests` must drop rows with an unverifiable `senderSignature` (unknown sender, or a
  * genuine contact's key but a forged/mismatched signature) instead of trusting whatever the relay returns, and
  * `respond` must reject explicitly.
  */
class ShareServiceSignatureTests extends munit.FunSuite:

  private val aliceKeys = TestKeyPair.generate()
  private val strangerKeys = TestKeyPair.generate()

  private val aliceContact = Contact(
    id = UUID.randomUUID(),
    pseudonym = "alice",
    verifyKey = aliceKeys.publicKey,
    encKey = Array.fill(32)(0x01.toByte),
    verificationLevel = VerificationLevel.VeryHigh,
    verifiedAt = None,
    addedAt = Instant.now()
  )

  private def newService(
      relay: FakeShareRelay,
      contacts: List[Contact] = List(aliceContact)
  ): (
      ShareService,
      IdentityService,
      FakeShareRepository,
      FakeContactRepository,
      FakeShareMetadataRepository,
      FakeKeyConflictRepository,
      FakeRetainedDepositRepository
  ) =
    val bobIdentity = IdentityService(InMemoryForgettableIdentityStore())
    bobIdentity.register("bob")
    val shareRepo = FakeShareRepository()
    val contactRepo = FakeContactRepository(contacts)
    val metaRepo = FakeShareMetadataRepository()
    val conflictRepo = FakeKeyConflictRepository()
    val retainedRepo = FakeRetainedDepositRepository()
    val svc = ShareService(
      relayResolver = FixedShareRelayResolver(relay),
      encryption = NoOpShareEncryption,
      shareRepository = shareRepo,
      shareMetadataRepository = metaRepo,
      secretRepository = FakeSecretRepository(),
      contactRepository = contactRepo,
      contactManagement = ContactService(contactRepo),
      keyConflictRepository = conflictRepo,
      retainedDepositRepository = retainedRepo,
      identity = bobIdentity
    )
    (svc, bobIdentity, shareRepo, contactRepo, metaRepo, conflictRepo, retainedRepo)

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
      PayloadCanonical.forOpen(
        row.secretId,
        row.transactionType,
        row.recipientKey,
        row.label,
        row.secretCreatedAt,
        row.shareId,
        row.ciphertext,
        row.k,
        row.n
      )
    )

  test("syncInbox approves and saves a Deposit with a valid senderSignature from a known contact") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo, _, _, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, aliceKeys.publicKey, bob.verifyKey(), Array.empty)
    val row = unsigned.copy(senderSignature = signOpenAs(aliceKeys, unsigned))
    relay.pending = List(row)
    relay.byId = Map(id -> row)

    svc.syncInbox()

    assertEquals(relay.respondCalls, List(id))
    assertEquals(shareRepo.getAll().map(_.id), List(id))
  }

  test("syncInbox skips a Deposit whose senderSignature doesn't verify against the claimed sender") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo, _, _, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, aliceKeys.publicKey, bob.verifyKey(), Array.empty)
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
    val (svc, bob, shareRepo, _, _, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, strangerKeys.publicKey, bob.verifyKey(), Array.empty)
    val row = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.pending = List(row)
    relay.byId = Map(id -> row)

    svc.syncInbox()

    assert(relay.respondCalls.isEmpty)
    assert(shareRepo.getAll().isEmpty)
  }

  test("listPendingRequests filters out a row with an unverifiable senderSignature") {
    val relay = FakeShareRelay()
    val (svc, bob, _, _, _, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, aliceKeys.publicKey, bob.verifyKey(), Array.empty)
      .copy(transactionType = ShareTransactionType.Removal)
    val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.pending = List(forged)

    assertEquals(svc.listPendingRequests(), List.empty)
  }

  test("respond throws SignatureVerificationException when senderSignature doesn't verify") {
    val relay = FakeShareRelay()
    val (svc, bob, _, _, _, _, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = depositRow(id, aliceKeys.publicKey, bob.verifyKey(), Array.empty)
      .copy(transactionType = ShareTransactionType.Removal)
    val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.byId = Map(id -> forged)

    intercept[SignatureVerificationException] {
      svc.respond(id, approved = true)
    }
  }

  // ── Identity recovery (item 8) ──────────────────────────────────────────────

  private def newServiceForRecoveryTest(
      relay: FakeShareRelay,
      contacts: List[Contact] = List(aliceContact)
  ): (ShareService, IdentityService, FakeShareRepository, FakeSecretRepository, FakeShareMetadataRepository) =
    val bobIdentity = IdentityService(InMemoryForgettableIdentityStore())
    bobIdentity.register("bob")
    val shareRepo = FakeShareRepository()
    val secretRepo = FakeSecretRepository()
    val metaRepo = FakeShareMetadataRepository()
    val contactRepo = FakeContactRepository(contacts)
    val svc = ShareService(
      relayResolver = FixedShareRelayResolver(relay),
      encryption = NoOpShareEncryption,
      shareRepository = shareRepo,
      shareMetadataRepository = metaRepo,
      secretRepository = secretRepo,
      contactRepository = contactRepo,
      contactManagement = ContactService(contactRepo),
      keyConflictRepository = FakeKeyConflictRepository(),
      retainedDepositRepository = FakeRetainedDepositRepository(),
      identity = bobIdentity
    )
    (svc, bobIdentity, shareRepo, secretRepo, metaRepo)

  /** A self-approved recoveryMetadata row, as the relay would hand it back — Approved state and respondedAt set at
    * creation, since this type has no consent phase (see item 8).
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
    val canon = PayloadCanonical.forOpen(
      secretId,
      ShareTransactionType.Inventory,
      recipientKey,
      label,
      createdAt,
      None,
      None,
      Some(k),
      Some(n)
    )
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
    assert(opened.recipientKey.sameElements(aliceContact.verifyKey))
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
    val pushRow = approvedRecoveryMetadataRow(secretId, aliceKeys.publicKey, bob.verifyKey(), aliceKeys)
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
    val pushRow = approvedRecoveryMetadataRow(secretId, aliceKeys.publicKey, bob.verifyKey(), strangerKeys)
    relay.pending = List(pushRow)

    svc.syncInbox()

    assert(secretRepo.getAll().isEmpty)
    assert(metaRepo.getAll().isEmpty)
    assert(relay.deletedRequestIds.isEmpty)
  }

  // ── Item 9: rotation push (client primitive + receive-side) and withdraw tombstone ───────────

  /** Builds a signed KeyRotation notice — the signing counterpart of `relay.pushRotation`. `signer` is the party whose
    * signature is attached; pass something other than the keypair backing `oldVerifyKey` to build a forged notice.
    */
  private def signedRotation(
      oldVerifyKey: Array[Byte],
      recipientKey: Array[Byte],
      signer: TestKeyPair,
      newVerifyKey: Array[Byte] = Array.fill(32)(0x0a.toByte),
      newEncKey: Array[Byte] = Array.fill(32)(0x0b.toByte),
      newCipherSuite: CipherSuite = CipherSuite.current
  ): KeyRotation =
    val canon = PayloadCanonical.forRotation(recipientKey, newVerifyKey, newEncKey, newCipherSuite)
    val sig = signer.sign(canon)
    KeyRotation(
      UUID.randomUUID(),
      oldVerifyKey,
      recipientKey,
      newVerifyKey,
      newEncKey,
      newCipherSuite,
      sig,
      Instant.now()
    )

  test("pushRotation signs with the current identity and pushes to the contact's relay") {
    val relay = FakeShareRelay()
    val (svc, bob, _, _, _, _, _) = newService(relay)
    val newEd = Array.fill(32)(0x08.toByte)
    val newX = Array.fill(32)(0x09.toByte)

    svc.pushRotation(aliceContact.id, newEd, newX, CipherSuite.current)

    assertEquals(relay.pushedRotations.size, 1)
    val pushed = relay.pushedRotations.head
    assert(pushed.recipientKey.sameElements(aliceContact.verifyKey))
    assert(pushed.newVerifyKey.sameElements(newEd))
    assert(pushed.newEncKey.sameElements(newX))
    assertEquals(pushed.newCipherSuite, CipherSuite.current)
    val canon = PayloadCanonical.forRotation(aliceContact.verifyKey, newEd, newX, CipherSuite.current)
    assert(bob.verify(canon, pushed.signature, bob.verifyKey()))
  }

  test("pushRotation throws for an unknown contact") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, _, _, _) = newService(relay)

    intercept[IllegalStateException] {
      svc.pushRotation(UUID.randomUUID(), Array.fill(32)(0x01.toByte), Array.fill(32)(0x02.toByte), CipherSuite.current)
    }
  }

  test("syncInbox auto-accepts a valid rotation notice and downgrades verification level to Low") {
    val relay = FakeShareRelay()
    // aliceContact starts at VeryHigh.
    val (svc, bob, _, contactRepo, _, _, _) = newService(relay)
    val newEd = Array.fill(32)(0x0c.toByte)
    val newX = Array.fill(32)(0x0d.toByte)
    val notice = signedRotation(aliceKeys.publicKey, bob.verifyKey(), aliceKeys, newEd, newX)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    val updated = contactRepo.getById(aliceContact.id).getOrElse(fail("contact missing"))
    assertEquals(updated.id, aliceContact.id) // updated in place, contactId preserved
    assert(updated.verifyKey.sameElements(newEd))
    assert(updated.encKey.sameElements(newX))
    assertEquals(updated.verificationLevel, VerificationLevel.Low)
    // Item 14 — the notice's cipherSuite is threaded through to the updated contact record.
    assertEquals(updated.cipherSuite, notice.newCipherSuite)
    assertEquals(relay.deletedRotationIds, List(notice.id))
  }

  test("syncInbox never raises verification level above Low even from an already-lower level") {
    val relay = FakeShareRelay()
    val daveKeys = TestKeyPair.generate()
    val daveContact = aliceContact.copy(
      id = UUID.randomUUID(),
      pseudonym = "dave",
      verifyKey = daveKeys.publicKey,
      verificationLevel = VerificationLevel.VeryLow
    )
    val (svc, bob, _, contactRepo, _, _, _) = newService(relay, contacts = List(daveContact))
    val notice = signedRotation(daveKeys.publicKey, bob.verifyKey(), daveKeys)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    // Continuity of key control is not a fresh personhood check (item 10) — it can never raise
    // the level, only cap it at Low.
    assertEquals(contactRepo.getById(daveContact.id).map(_.verificationLevel), Some(VerificationLevel.VeryLow))
  }

  test("syncInbox ignores a rotation notice with a forged signature") {
    val relay = FakeShareRelay()
    val (svc, bob, _, contactRepo, _, _, _) = newService(relay)
    // Claims to be from alice (oldVerifyKey = aliceKeys.publicKey) but signed by a stranger.
    val notice = signedRotation(aliceKeys.publicKey, bob.verifyKey(), strangerKeys)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    assert(contactRepo.getById(aliceContact.id).exists(_.verifyKey.sameElements(aliceContact.verifyKey)))
    assert(relay.deletedRotationIds.isEmpty)
  }

  test("syncInbox ignores a rotation notice from an unknown old key") {
    val relay = FakeShareRelay()
    val (svc, bob, _, contactRepo, _, _, _) = newService(relay)
    val notice = signedRotation(strangerKeys.publicKey, bob.verifyKey(), strangerKeys)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    assertEquals(contactRepo.getAll(), List(aliceContact))
    assert(relay.deletedRotationIds.isEmpty)
  }

  test("deleteHeldShare withdraws from the sender's relay scoped by secretId then deletes locally") {
    val relay = FakeShareRelay()
    val (svc, _, shareRepo, _, _, _, _) = newService(relay)
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
    val (svc, _, shareRepo, _, _, _, _) = newService(relay)
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
    assert(relay.withdrawCalls.head.senderKey.exists(_.sameElements(aliceContact.verifyKey)))
    assertEquals(relay.withdrawCalls.head.secretId, None)
    assert(shareRepo.getAll().isEmpty)
  }

  test("deleteHeldShare still deletes locally even if the withdraw call fails") {
    val relay = FakeShareRelay()
    relay.throwOnWithdraw = true
    val (svc, _, shareRepo, _, _, _, _) = newService(relay)
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

  /** A bare deposit row shaped only for `syncDistributed()`'s purposes — that method never checks signatures, so
    * `senderSignature` is deliberately empty filler, not a genuine one.
    */
  private def bareDepositRow(
      id: UUID,
      secretId: UUID,
      recipientKey: Array[Byte],
      state: ShareRequestState
  ): ShareRequest =
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
    val (svc, _, _, _, metaRepo, _, _) = newService(relay)
    val depositId = UUID.randomUUID()
    val secretId = UUID.randomUUID()
    metaRepo.save(ShareMetadata(depositId, secretId, aliceContact.id))
    relay.pending = List(bareDepositRow(depositId, secretId, aliceContact.verifyKey, ShareRequestState.Withdrawn))

    svc.syncDistributed()

    assert(metaRepo.getAll().isEmpty)
    assertEquals(relay.deletedRequestIds, List(depositId))
  }

  test("syncDistributed still upserts normally for a non-withdrawn row") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, metaRepo, _, _) = newService(relay)
    val depositId = UUID.randomUUID()
    val secretId = UUID.randomUUID()
    relay.pending = List(bareDepositRow(depositId, secretId, aliceContact.verifyKey, ShareRequestState.Approved))

    svc.syncDistributed()

    assertEquals(metaRepo.getAll().map(_.id), List(depositId))
    assert(relay.deletedRequestIds.isEmpty)
  }

  // ── Item 10: stolen-key revocation (compromised-key flag + key conflicts) ────────────────────

  test("syncInbox refuses auto-accept and captures a key conflict when the old key is revoked") {
    val relay = FakeShareRelay()
    val revokedAliceContact = aliceContact.copy(revokedVerifyKeys = List(aliceKeys.publicKey))
    val (svc, bob, _, contactRepo, _, conflictRepo, _) = newService(relay, contacts = List(revokedAliceContact))
    val newEd = Array.fill(32)(0x0e.toByte)
    val newX = Array.fill(32)(0x0f.toByte)
    val notice = signedRotation(aliceKeys.publicKey, bob.verifyKey(), aliceKeys, newEd, newX)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    // Not auto-accepted: the contact record is untouched.
    val stillCurrent = contactRepo.getById(revokedAliceContact.id).getOrElse(fail("contact missing"))
    assert(stillCurrent.verifyKey.sameElements(revokedAliceContact.verifyKey))
    assertEquals(stillCurrent.verificationLevel, VerificationLevel.VeryHigh)
    // Captured locally instead — durable, not dependent on the relay still having the notice.
    val conflicts = conflictRepo.getAll()
    assertEquals(conflicts.size, 1)
    assertEquals(conflicts.head.contactId, revokedAliceContact.id)
    assert(conflicts.head.newVerifyKey.sameElements(newEd))
    assert(conflicts.head.newEncKey.sameElements(newX))
    // The relay notice is consumed either way — the local KeyConflict record is now the durable copy.
    assertEquals(relay.deletedRotationIds, List(notice.id))
  }

  test("syncInbox still auto-accepts a non-revoked rotation") {
    val relay = FakeShareRelay()
    // Some unrelated historical key, not the one this notice claims continuity from.
    val contactWithUnrelatedRevocation = aliceContact.copy(revokedVerifyKeys = List(Array.fill(32)(0x99.toByte)))
    val (svc, bob, _, contactRepo, _, conflictRepo, _) =
      newService(relay, contacts = List(contactWithUnrelatedRevocation))
    val newEd = Array.fill(32)(0x10.toByte)
    val notice = signedRotation(aliceKeys.publicKey, bob.verifyKey(), aliceKeys, newEd)
    relay.rotationsToReturn = List(notice)

    svc.syncInbox()

    assert(contactRepo.getById(aliceContact.id).exists(_.verifyKey.sameElements(newEd)))
    assert(conflictRepo.getAll().isEmpty)
  }

  test("listAndDismissKeyConflict round-trips") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, _, conflictRepo, _) = newService(relay)
    val conflict = KeyConflict(
      id = UUID.randomUUID(),
      contactId = aliceContact.id,
      oldVerifyKey = aliceKeys.publicKey,
      newVerifyKey = Array.fill(32)(0x01.toByte),
      newEncKey = Array.fill(32)(0x02.toByte),
      detectedAt = Instant.now()
    )
    conflictRepo.save(conflict)

    assertEquals(svc.listKeyConflicts(), List(conflict))

    svc.dismissKeyConflict(conflict.id)

    assert(svc.listKeyConflicts().isEmpty)
  }

  // ── Item 12: custodial heartbeats + deposit retention ────────────────────────

  private val holderTwoKeys = TestKeyPair.generate()
  private val holderTwoContact = aliceContact.copy(
    id = UUID.randomUUID(),
    pseudonym = "holderTwo",
    verifyKey = holderTwoKeys.publicKey
  )

  private def signedHeartbeat(
      holderKey: Array[Byte],
      ownerKey: Array[Byte],
      signer: TestKeyPair,
      secretIds: Seq[UUID] = Nil,
      optedOut: Boolean = false
  ): CustodyHeartbeat =
    val canon = PayloadCanonical.forHeartbeat(ownerKey, secretIds, optedOut)
    CustodyHeartbeat(UUID.randomUUID(), holderKey, ownerKey, secretIds, optedOut, signer.sign(canon), Instant.now())

  test("deposit retains an encrypted blob per holder") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, _, _, retainedRepo) = newService(relay, contacts = List(aliceContact, holderTwoContact))

    svc.deposit(Array[Byte](1, 2, 3), "test secret", List(aliceContact, holderTwoContact), threshold = 2)

    val blobs = retainedRepo.getAll()
    assertEquals(blobs.size, 2)
    assert(blobs.forall(b => b.k == 2 && b.n == 2))
    assertEquals(blobs.map(_.contactId).toSet, Set(aliceContact.id, holderTwoContact.id))
  }

  test("syncDistributed stamps freshness and discards the retained blob on first observed approval") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, metaRepo, _, retainedRepo) = newService(relay)
    val depositId = UUID.randomUUID()
    val secretId = UUID.randomUUID()
    retainedRepo.save(
      RetainedDepositBlob(depositId, secretId, aliceContact.id, "test secret", Instant.now(), Array[Byte](9), 2, 3)
    )
    relay.pending = List(bareDepositRow(depositId, secretId, aliceContact.verifyKey, ShareRequestState.Approved))

    svc.syncDistributed()

    val meta = metaRepo.getAll().find(_.id == depositId).getOrElse(fail("metadata missing"))
    assert(meta.lastConfirmedAt.isDefined)
    assert(retainedRepo.getAll().isEmpty)
  }

  test("syncDistributed does not refresh freshness on a subsequent poll of an already-confirmed row") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, metaRepo, _, retainedRepo) = newService(relay)
    val depositId = UUID.randomUUID()
    val secretId = UUID.randomUUID()
    retainedRepo.save(
      RetainedDepositBlob(depositId, secretId, aliceContact.id, "test secret", Instant.now(), Array[Byte](9), 2, 3)
    )
    relay.pending = List(bareDepositRow(depositId, secretId, aliceContact.verifyKey, ShareRequestState.Approved))

    svc.syncDistributed()
    val firstConfirmedAt =
      metaRepo.getAll().find(_.id == depositId).flatMap(_.lastConfirmedAt).getOrElse(fail("not confirmed"))

    // Second poll: the relay still returns the same already-approved row, and the retained blob
    // is already gone — must not bump freshness again.
    svc.syncDistributed()
    val secondConfirmedAt =
      metaRepo.getAll().find(_.id == depositId).flatMap(_.lastConfirmedAt).getOrElse(fail("not confirmed"))

    assertEquals(firstConfirmedAt, secondConfirmedAt)
  }

  test("syncDistributed stamps freshness from an approved retrieval") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, metaRepo, _, _) = newService(relay)
    val depositId = UUID.randomUUID()
    val secretId = UUID.randomUUID()
    metaRepo.save(ShareMetadata(depositId, secretId, aliceContact.id))
    val retrievalRow = bareDepositRow(UUID.randomUUID(), secretId, aliceContact.verifyKey, ShareRequestState.Approved)
      .copy(transactionType = ShareTransactionType.Retrieval, shareId = Some(depositId))
    relay.pending = List(retrievalRow)

    svc.syncDistributed()

    val meta = metaRepo.getAll().find(_.id == depositId).getOrElse(fail("metadata missing"))
    assert(meta.lastConfirmedAt.isDefined)
  }

  test("syncInbox emits a heartbeat to each distinct sender this device holds a share from") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo, _, _, _, _) = newService(relay)
    val secretId = UUID.randomUUID()
    shareRepo.save(
      HeldShare(
        UUID.randomUUID(),
        secretId,
        "test secret",
        aliceContact.id,
        Instant.now(),
        Instant.now(),
        Array[Byte](1),
        2,
        3
      )
    )

    svc.syncInbox()

    assertEquals(relay.pushedHeartbeats.size, 1)
    val pushed = relay.pushedHeartbeats.head
    assert(pushed.ownerKey.sameElements(aliceContact.verifyKey))
    assertEquals(pushed.secretIds, Seq(secretId))
    assertEquals(pushed.optedOut, false)
    val canon = PayloadCanonical.forHeartbeat(aliceContact.verifyKey, Seq(secretId), false)
    assert(bob.verify(canon, pushed.signature, bob.verifyKey()))
  }

  test("syncInbox does not re-emit a heartbeat before the emission interval elapses") {
    val relay = FakeShareRelay()
    val recentlySentContact = aliceContact.copy(lastHeartbeatSentAt = Some(Instant.now()))
    val (svc, _, shareRepo, _, _, _, _) = newService(relay, contacts = List(recentlySentContact))
    shareRepo.save(
      HeldShare(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "test secret",
        recentlySentContact.id,
        Instant.now(),
        Instant.now(),
        Array[Byte](1),
        2,
        3
      )
    )

    svc.syncInbox()

    assert(relay.pushedHeartbeats.isEmpty)
  }

  test("syncInbox sends an opt-out notice with empty secretIds when heartbeat emission is opted out") {
    val relay = FakeShareRelay()
    val optedOutContact = aliceContact.copy(heartbeatEmissionOptedOut = true)
    val (svc, _, shareRepo, _, _, _, _) = newService(relay, contacts = List(optedOutContact))
    val secretId = UUID.randomUUID()
    shareRepo.save(
      HeldShare(
        UUID.randomUUID(),
        secretId,
        "test secret",
        optedOutContact.id,
        Instant.now(),
        Instant.now(),
        Array[Byte](1),
        2,
        3
      )
    )

    svc.syncInbox()

    assertEquals(relay.pushedHeartbeats.size, 1)
    val pushed = relay.pushedHeartbeats.head
    assertEquals(pushed.secretIds, Seq.empty)
    assertEquals(pushed.optedOut, true)
  }

  test("syncInbox does not advance lastHeartbeatSentAt when the push fails") {
    val relay = FakeShareRelay()
    relay.throwOnPushHeartbeat = true
    val (svc, _, shareRepo, contactRepo, _, _, _) = newService(relay)
    shareRepo.save(
      HeldShare(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "test secret",
        aliceContact.id,
        Instant.now(),
        Instant.now(),
        Array[Byte](1),
        2,
        3
      )
    )

    svc.syncInbox()

    assert(contactRepo.getById(aliceContact.id).flatMap(_.lastHeartbeatSentAt).isEmpty)
  }

  test("syncDistributed processes a valid heartbeat and stamps freshness on matching shares") {
    val relay = FakeShareRelay()
    val (svc, bob, _, _, metaRepo, _, retainedRepo) = newService(relay)
    val depositId = UUID.randomUUID()
    val secretId = UUID.randomUUID()
    metaRepo.save(ShareMetadata(depositId, secretId, aliceContact.id))
    retainedRepo.save(
      RetainedDepositBlob(depositId, secretId, aliceContact.id, "test secret", Instant.now(), Array[Byte](9), 2, 3)
    )
    val notice = signedHeartbeat(aliceKeys.publicKey, bob.verifyKey(), aliceKeys, secretIds = Seq(secretId))
    relay.heartbeatsToReturn = List(notice)

    svc.syncDistributed()

    val meta = metaRepo.getAll().find(_.id == depositId).getOrElse(fail("metadata missing"))
    assert(meta.lastConfirmedAt.isDefined)
    assert(retainedRepo.getAll().isEmpty)
  }

  test("syncDistributed records an opt-out heartbeat without touching share metadata") {
    val relay = FakeShareRelay()
    val (svc, bob, _, contactRepo, metaRepo, _, _) = newService(relay)
    val depositId = UUID.randomUUID()
    val secretId = UUID.randomUUID()
    metaRepo.save(ShareMetadata(depositId, secretId, aliceContact.id))
    val notice = signedHeartbeat(aliceKeys.publicKey, bob.verifyKey(), aliceKeys, optedOut = true)
    relay.heartbeatsToReturn = List(notice)

    svc.syncDistributed()

    val updated = contactRepo.getById(aliceContact.id).getOrElse(fail("contact missing"))
    assert(updated.heartbeatOptedOutAt.isDefined)
    assert(metaRepo.getAll().find(_.id == depositId).flatMap(_.lastConfirmedAt).isEmpty)
  }

  test("syncDistributed clears a prior opt-out once the holder resumes heartbeating") {
    val relay = FakeShareRelay()
    val previouslyOptedOutContact = aliceContact.copy(heartbeatOptedOutAt = Some(Instant.now().minusSeconds(3600)))
    val (svc, bob, _, contactRepo, _, _, _) = newService(relay, contacts = List(previouslyOptedOutContact))
    val notice = signedHeartbeat(aliceKeys.publicKey, bob.verifyKey(), aliceKeys, optedOut = false)
    relay.heartbeatsToReturn = List(notice)

    svc.syncDistributed()

    assert(contactRepo.getById(previouslyOptedOutContact.id).flatMap(_.heartbeatOptedOutAt).isEmpty)
  }

  test("syncDistributed ignores a heartbeat with a forged signature") {
    val relay = FakeShareRelay()
    val (svc, bob, _, contactRepo, _, _, _) = newService(relay)
    val notice = signedHeartbeat(aliceKeys.publicKey, bob.verifyKey(), strangerKeys)
    relay.heartbeatsToReturn = List(notice)

    svc.syncDistributed()

    assertEquals(contactRepo.getAll(), List(aliceContact))
  }

  test("setHeartbeatEmissionOptedOut toggles the flag and resets lastHeartbeatSentAt") {
    val relay = FakeShareRelay()
    val sentContact = aliceContact.copy(lastHeartbeatSentAt = Some(Instant.now()))
    val (svc, _, _, contactRepo, _, _, _) = newService(relay, contacts = List(sentContact))

    svc.setHeartbeatEmissionOptedOut(sentContact.id, optedOut = true)

    val updated = contactRepo.getById(sentContact.id).getOrElse(fail("contact missing"))
    assertEquals(updated.heartbeatEmissionOptedOut, true)
    assert(updated.lastHeartbeatSentAt.isEmpty)
  }

  test("setHeartbeatEmissionOptedOut throws for an unknown contact") {
    val relay = FakeShareRelay()
    val (svc, _, _, _, _, _, _) = newService(relay)

    intercept[IllegalStateException] {
      svc.setHeartbeatEmissionOptedOut(UUID.randomUUID(), optedOut = true)
    }
  }

  // ── Reconstruction integrity + fan-out targeting (item 13) ──────────────────

  /** A holder contact with its own real keypair — reconstruct() tests need several distinct holders (unlike most of
    * this file's single-contact fixtures), each independently able to produce a validly-signed recipientSignature on
    * its own retrieval response.
    */
  private case class HolderFixture(keys: TestKeyPair, contact: Contact)

  private def makeHolderFixture(pseudonym: String): HolderFixture =
    val keys = TestKeyPair.generate()
    val contact = Contact(
      id = UUID.randomUUID(),
      pseudonym = pseudonym,
      verifyKey = keys.publicKey,
      encKey = Array.fill(32)(0x09.toByte),
      verificationLevel = VerificationLevel.VeryHigh,
      verifiedAt = None,
      addedAt = Instant.now()
    )
    HolderFixture(keys, contact)

  /** An already-Approved retrieval response row, signed by the holder — mirrors what respond() would have produced.
    * ciphertext is used as-is by NoOpShareEncryption, so passing a real split() share here makes it stand in directly
    * as the "decrypted" plaintext.
    */
  private def makeApprovedRetrievalRow(secretId: UUID, holder: HolderFixture, ciphertext: Array[Byte]): ShareRequest =
    val id = UUID.randomUUID()
    val canon = PayloadCanonical.forRespond(id, true, Some(ciphertext))
    val sig = holder.keys.sign(canon)
    ShareRequest(
      id = id,
      secretId = secretId,
      senderKey = Array.emptyByteArray,
      recipientKey = holder.contact.verifyKey,
      label = "s",
      secretCreatedAt = Instant.now(),
      transactionType = ShareTransactionType.Retrieval,
      state = ShareRequestState.Approved,
      shareId = Some(UUID.randomUUID()),
      requestedAt = Instant.now(),
      respondedAt = Some(Instant.now()),
      ciphertext = Some(ciphertext),
      senderSignature = Array.emptyByteArray,
      recipientSignature = Some(sig)
    )

  /** A still-Pending retrieval row, as a previous requestAll would have left it — no recipientSignature, because a
    * pending row has had no response phase yet.
    */
  private def makePendingRetrievalRow(secretId: UUID, recipientKey: Array[Byte]): ShareRequest =
    ShareRequest(
      id = UUID.randomUUID(),
      secretId = secretId,
      senderKey = Array.emptyByteArray,
      recipientKey = recipientKey,
      label = "s",
      secretCreatedAt = Instant.now(),
      transactionType = ShareTransactionType.Retrieval,
      state = ShareRequestState.Pending,
      shareId = Some(UUID.randomUUID()),
      requestedAt = Instant.now(),
      respondedAt = None,
      ciphertext = None,
      senderSignature = Array.emptyByteArray,
      recipientSignature = None
    )

  test("reconstruct with exactly k approved shares has no integrity margin") {
    val relay = FakeShareRelay()
    val holders = (0 until 4).map(i => makeHolderFixture(s"holder$i")).toList
    val (svc, _, _, secretRepo, _) = newServiceForRecoveryTest(relay, holders.map(_.contact))
    val secretBytes = "no margin test secret".getBytes("UTF-8")
    val shares = SecretSharing.split(secretBytes, shares = 4, threshold = 4)
    val secretId = UUID.randomUUID()
    secretRepo.save(Secret(secretId, "s", 4, 4, Instant.now(), SecretState.Active))
    relay.pending = holders.zip(shares).map((holder, share) => makeApprovedRetrievalRow(secretId, holder, share))

    val result = svc.reconstruct(secretId)

    assertEquals(result.secret.toList, secretBytes.toList)
    assertEquals(result.integrity, ReconstructionIntegrity.NoMargin)
  }

  test("reconstruct with surplus all consistent shares is confirmed") {
    val relay = FakeShareRelay()
    val holders = (0 until 5).map(i => makeHolderFixture(s"holder$i")).toList
    val (svc, _, _, secretRepo, _) = newServiceForRecoveryTest(relay, holders.map(_.contact))
    val secretBytes = "surplus confirmed test secret".getBytes("UTF-8")
    val shares = SecretSharing.split(secretBytes, shares = 5, threshold = 4)
    val secretId = UUID.randomUUID()
    secretRepo.save(Secret(secretId, "s", 4, 5, Instant.now(), SecretState.Active))
    relay.pending = holders.zip(shares).map((holder, share) => makeApprovedRetrievalRow(secretId, holder, share))

    val result = svc.reconstruct(secretId)

    assertEquals(result.secret.toList, secretBytes.toList)
    assertEquals(result.integrity, ReconstructionIntegrity.Confirmed)
  }

  test("reconstruct excludes a tampered share and still reconstructs correctly") {
    val relay = FakeShareRelay()
    val holders = (0 until 6).map(i => makeHolderFixture(s"holder$i")).toList
    val (svc, _, _, secretRepo, _) = newServiceForRecoveryTest(relay, holders.map(_.contact))
    val secretBytes = "excluded suspect test secret".getBytes("UTF-8")
    val shares = SecretSharing.split(secretBytes, shares = 6, threshold = 4).toArray
    // Simulate a compromised/corrupted holder — every secret byte wrong, x-coordinate untouched.
    val tampered = shares(2).clone()
    for i <- 0 until tampered.length - 1 do tampered(i) = (tampered(i) + 1).toByte
    shares(2) = tampered
    val secretId = UUID.randomUUID()
    secretRepo.save(Secret(secretId, "s", 4, 6, Instant.now(), SecretState.Active))
    relay.pending = holders.zip(shares.toList).map((holder, share) => makeApprovedRetrievalRow(secretId, holder, share))

    val result = svc.reconstruct(secretId)

    assertEquals(result.secret.toList, secretBytes.toList)
    assertEquals(result.integrity, ReconstructionIntegrity.ExcludedSuspects(Set(holders(2).contact.id)))
  }

  test("reconstruct throws when too many shares are inconsistent to safely resolve") {
    val relay = FakeShareRelay()
    val holders = (0 until 5).map(i => makeHolderFixture(s"holder$i")).toList
    val (svc, _, _, secretRepo, _) = newServiceForRecoveryTest(relay, holders.map(_.contact))
    val secretBytes = "margin one throw test".getBytes("UTF-8")
    val shares = SecretSharing.split(secretBytes, shares = 5, threshold = 4).toArray
    val tampered = shares(0).clone()
    for i <- 0 until tampered.length - 1 do tampered(i) = (tampered(i) + 1).toByte
    shares(0) = tampered
    val secretId = UUID.randomUUID()
    secretRepo.save(Secret(secretId, "s", 4, 5, Instant.now(), SecretState.Active))
    relay.pending = holders.zip(shares.toList).map((holder, share) => makeApprovedRetrievalRow(secretId, holder, share))

    intercept[SecretSharing.ReconstructionIntegrityException] {
      svc.reconstruct(secretId)
    }
  }

  test("requestAll targets only confirmed holders when they already meet k") {
    val relay = FakeShareRelay()
    val fresh1 = makeHolderFixture("fresh1")
    val fresh2 = makeHolderFixture("fresh2")
    val stale = makeHolderFixture("stale")
    val (svc, _, _, secretRepo, metaRepo) =
      newServiceForRecoveryTest(relay, List(fresh1.contact, fresh2.contact, stale.contact))
    val secretId = UUID.randomUUID()
    secretRepo.save(Secret(secretId, "s", 2, 3, Instant.now(), SecretState.Active))
    val now = Instant.now()
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, fresh1.contact.id, lastConfirmedAt = Some(now)))
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, fresh2.contact.id, lastConfirmedAt = Some(now)))
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, stale.contact.id, lastConfirmedAt = None))

    svc.requestAll(secretId)

    val targeted = relay.openedRequests.map(_.recipientKey.toList).toSet
    val expected = Set(fresh1.contact.verifyKey.toList, fresh2.contact.verifyKey.toList)
    assertEquals(targeted, expected)
  }

  test("requestAll widens to every holder when fewer than k are confirmed") {
    val relay = FakeShareRelay()
    val fresh = makeHolderFixture("fresh")
    val stale1 = makeHolderFixture("stale1")
    val stale2 = makeHolderFixture("stale2")
    val (svc, _, _, secretRepo, metaRepo) =
      newServiceForRecoveryTest(relay, List(fresh.contact, stale1.contact, stale2.contact))
    val secretId = UUID.randomUUID()
    secretRepo.save(Secret(secretId, "s", 2, 3, Instant.now(), SecretState.Active))
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, fresh.contact.id, lastConfirmedAt = Some(Instant.now())))
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, stale1.contact.id, lastConfirmedAt = None))
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, stale2.contact.id, lastConfirmedAt = None))

    svc.requestAll(secretId)

    val targeted = relay.openedRequests.map(_.recipientKey.toList).toSet
    val expected = Set(fresh.contact.verifyKey.toList, stale1.contact.verifyKey.toList, stale2.contact.verifyKey.toList)
    assertEquals(targeted, expected)
  }

  test("requestAll still asks a holder whose sibling already has an outstanding request") {
    val relay = FakeShareRelay()
    val standing = makeHolderFixture("standing")
    val untouched = makeHolderFixture("untouched")
    val (svc, _, _, secretRepo, metaRepo) =
      newServiceForRecoveryTest(relay, List(standing.contact, untouched.contact))
    val secretId = UUID.randomUUID()
    secretRepo.save(Secret(secretId, "s", 2, 2, Instant.now(), SecretState.Active))
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, standing.contact.id, lastConfirmedAt = None))
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, untouched.contact.id, lastConfirmedAt = None))
    // Neither holder is confirmed, so targeting widens to both — the case the per-secret skip used
    // to blank out entirely. The row carries a *copy* of the key, as a relay round-trip would, so
    // the skip has to compare bytes: reference identity would pass this fixture by accident.
    relay.pending = List(makePendingRetrievalRow(secretId, standing.contact.verifyKey.clone()))

    svc.requestAll(secretId)

    val targeted = relay.openedRequests.map(_.recipientKey.toList)
    assertEquals(targeted, List(untouched.contact.verifyKey.toList))
  }

  test("requestAll treats a heartbeat opted-out holder as not confirmed even with a recent timestamp") {
    val relay = FakeShareRelay()
    val optedOutBase = makeHolderFixture("optedOut")
    val optedOutContact = optedOutBase.contact.copy(heartbeatOptedOutAt = Some(Instant.now()))
    val other = makeHolderFixture("other")
    val (svc, _, _, secretRepo, metaRepo) =
      newServiceForRecoveryTest(relay, List(optedOutContact, other.contact))
    val secretId = UUID.randomUUID()
    secretRepo.save(Secret(secretId, "s", 2, 2, Instant.now(), SecretState.Active))
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, optedOutContact.id, lastConfirmedAt = Some(Instant.now())))
    metaRepo.save(ShareMetadata(UUID.randomUUID(), secretId, other.contact.id, lastConfirmedAt = None))

    svc.requestAll(secretId)

    // Only 1 of 2 holders is genuinely confirmed (< k=2), so targeting widens to everyone.
    val targeted = relay.openedRequests.map(_.recipientKey.toList).toSet
    val expected = Set(optedOutContact.verifyKey.toList, other.contact.verifyKey.toList)
    assertEquals(targeted, expected)
  }

  // ── Identity regeneration (item 9's parked "regenerate my own identity" trigger) ────────────

  test("regenerateIdentity pushes a signed rotation to every contact and activates the new keys") {
    val relay = FakeShareRelay()
    val charlieKeys = TestKeyPair.generate()
    val charlieContact = Contact(
      id = UUID.randomUUID(),
      pseudonym = "charlie",
      verifyKey = charlieKeys.publicKey,
      encKey = Array.fill(32)(0x02.toByte),
      verificationLevel = VerificationLevel.VeryHigh,
      verifiedAt = None,
      addedAt = Instant.now()
    )
    val (svc, bob, _, _, _, _, _) = newService(relay, List(aliceContact, charlieContact))
    val oldVerifyKey = bob.verifyKey()
    val oldEncKey = bob.encKey()

    val result = svc.regenerateIdentity()

    assertEquals(result.notifiedContacts, 2)
    assertEquals(result.totalContacts, 2)
    assertEquals(relay.pushedRotations.size, 2)
    relay.pushedRotations.foreach { pushed =>
      // Item 14 — asserts the device's current suite, unconditionally.
      assertEquals(pushed.newCipherSuite, CipherSuite.current)
      val canon =
        PayloadCanonical.forRotation(pushed.recipientKey, pushed.newVerifyKey, pushed.newEncKey, pushed.newCipherSuite)
      // Signed by the OLD identity, proving continuity — not by the key it's rotating to.
      assert(bob.verify(canon, pushed.signature, oldVerifyKey))
      assert(!bob.verify(canon, pushed.signature, pushed.newVerifyKey))
    }
    // The new identity is now live.
    assert(!bob.verifyKey().sameElements(oldVerifyKey))
    assert(!bob.encKey().sameElements(oldEncKey))
  }

  test("regenerateIdentity drains the pending inbox under the old identity before rotating") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo, _, _, _, _) = newService(relay)
    val oldVerifyKey = bob.verifyKey()
    val depositId = UUID.randomUUID()
    val unsigned = depositRow(depositId, aliceKeys.publicKey, bob.verifyKey(), Array.emptyByteArray)
    val row = unsigned.copy(senderSignature = signOpenAs(aliceKeys, unsigned))
    relay.pending = List(row)
    relay.byId += depositId -> row

    svc.regenerateIdentity()

    // The deposit was picked up and its recipientSignature was produced under the OLD identity —
    // proving the drain ran (and completed) before the keys were swapped.
    assertEquals(shareRepo.getAll().map(_.id), List(depositId))
    val approved = relay.byId(depositId)
    assertEquals(approved.state, ShareRequestState.Approved)
    val sig = approved.recipientSignature.getOrElse(fail("expected a recipientSignature"))
    val canon = PayloadCanonical.forRespond(depositId, true, None)
    assert(bob.verify(canon, sig, oldVerifyKey))
  }

  test("regenerateIdentity still activates the new keys when one contact's relay is unreachable") {
    val byorUrl = "http://byor.example:9000"
    val charlieKeys = TestKeyPair.generate()
    val charlieContact = Contact(
      id = UUID.randomUUID(),
      pseudonym = "charlie",
      verifyKey = charlieKeys.publicKey,
      encKey = Array.fill(32)(0x02.toByte),
      verificationLevel = VerificationLevel.VeryHigh,
      verifiedAt = None,
      addedAt = Instant.now(),
      relayBaseUrl = Some(byorUrl)
    )
    val defaultRelay = FakeShareRelay()
    val byorRelay = FakeShareRelay()
    byorRelay.throwOnPushRotation = true
    val bobIdentity = IdentityService(InMemoryForgettableIdentityStore())
    bobIdentity.register("bob")
    val contactRepo = FakeContactRepository(List(aliceContact, charlieContact))
    val svc = ShareService(
      relayResolver = TwoRelayResolver(defaultRelay, byorUrl, byorRelay),
      encryption = NoOpShareEncryption,
      shareRepository = FakeShareRepository(),
      shareMetadataRepository = FakeShareMetadataRepository(),
      secretRepository = FakeSecretRepository(),
      contactRepository = contactRepo,
      contactManagement = ContactService(contactRepo),
      keyConflictRepository = FakeKeyConflictRepository(),
      retainedDepositRepository = FakeRetainedDepositRepository(),
      identity = bobIdentity
    )
    val oldVerifyKey = bobIdentity.verifyKey()

    val result = svc.regenerateIdentity()

    assertEquals(result.totalContacts, 2)
    assertEquals(result.notifiedContacts, 1) // charlie's BYOR relay refused the push
    assertEquals(defaultRelay.pushedRotations.size, 1)
    assert(byorRelay.pushedRotations.isEmpty)
    // The swap still completes even though one contact couldn't be notified.
    assert(!bobIdentity.verifyKey().sameElements(oldVerifyKey))
  }
