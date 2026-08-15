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

private class FakeContactRepository(contacts: List[Contact]) extends ContactRepository:
  override def getAll(): List[Contact] = contacts
  override def getByEdKey(edPublicKey: Array[Byte]): Option[Contact] =
    contacts.find(_.edPublicKey.sameElements(edPublicKey))
  override def getById(id: UUID): Option[Contact] = contacts.find(_.id == id)
  override def save(contact: Contact): Unit = ()
  override def delete(contactId: UUID): Unit = ()

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
  case class OpenedRequest(secretId: UUID, recipientKey: Array[Byte], requestType: ShareRequestType, k: Option[Int], n: Option[Int])

  var pending: List[ShareRequest] = Nil
  var byId: Map[UUID, ShareRequest] = Map.empty
  var respondCalls: List[UUID] = Nil
  var deletedRequestIds: List[UUID] = Nil
  var openedRequests: List[OpenedRequest] = Nil

  override def openShareRequest(
      secretId: UUID,
      recipientKey: Array[Byte],
      label: String,
      secretCreatedAt: Instant,
      requestType: ShareRequestType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]],
      k: Option[Int],
      n: Option[Int],
      senderSignature: Array[Byte]
  ): ShareRequest =
    openedRequests :+= OpenedRequest(secretId, recipientKey, requestType, k, n)
    val selfApproved = requestType == ShareRequestType.RecoveryMetadata
    val now = Instant.now()
    ShareRequest(
      id = UUID.randomUUID(),
      secretId = secretId,
      senderKey = Array.emptyByteArray,
      recipientKey = recipientKey,
      label = label,
      secretCreatedAt = secretCreatedAt,
      requestType = requestType,
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
      requestType: Option[ShareRequestType],
      state: Option[ShareRequestState]
  ): List[ShareRequest] =
    if unreachable then throw RuntimeException("simulated relay outage")
    else pending.filter(r => requestType.forall(_ == r.requestType) && state.forall(_ == r.state))

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

  private def newService(relay: FakeShareRelay): (ShareService, IdentityService, FakeShareRepository) =
    val bobIdentity = IdentityService(InMemoryForgettableIdentityStore())
    bobIdentity.register("bob")
    val shareRepo = FakeShareRepository()
    val svc = ShareService(
      relayResolver = FixedShareRelayResolver(relay),
      encryption = NoOpShareEncryption,
      shareRepository = shareRepo,
      shareMetadataRepository = FakeShareMetadataRepository(),
      secretRepository = FakeSecretRepository(),
      contactRepository = FakeContactRepository(List(aliceContact)),
      identity = bobIdentity
    )
    (svc, bobIdentity, shareRepo)

  private def pickUpRow(
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
      requestType = ShareRequestType.PickUp,
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
      PayloadCanonical.forOpen(row.secretId, row.requestType, row.recipientKey, row.label, row.secretCreatedAt, row.shareId, row.ciphertext, row.k, row.n)
    )

  test("syncInbox approves and saves a PickUp with a valid senderSignature from a known contact") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = pickUpRow(id, aliceKeys.publicKey, bob.edPublicKey(), Array.empty)
    val row = unsigned.copy(senderSignature = signOpenAs(aliceKeys, unsigned))
    relay.pending = List(row)
    relay.byId = Map(id -> row)

    svc.syncInbox()

    assertEquals(relay.respondCalls, List(id))
    assertEquals(shareRepo.getAll().map(_.id), List(id))
  }

  test("syncInbox skips a PickUp whose senderSignature doesn't verify against the claimed sender") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = pickUpRow(id, aliceKeys.publicKey, bob.edPublicKey(), Array.empty)
    // Signed by a stranger, not by alice — claims to be from alice but doesn't verify against her key.
    val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.pending = List(forged)
    relay.byId = Map(id -> forged)

    svc.syncInbox()

    assert(relay.respondCalls.isEmpty)
    assert(shareRepo.getAll().isEmpty)
  }

  test("syncInbox skips a PickUp from an unknown sender even with a self-consistent signature") {
    val relay = FakeShareRelay()
    val (svc, bob, shareRepo) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = pickUpRow(id, strangerKeys.publicKey, bob.edPublicKey(), Array.empty)
    val row = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.pending = List(row)
    relay.byId = Map(id -> row)

    svc.syncInbox()

    assert(relay.respondCalls.isEmpty)
    assert(shareRepo.getAll().isEmpty)
  }

  test("listPendingRequests filters out a row with an unverifiable senderSignature") {
    val relay = FakeShareRelay()
    val (svc, bob, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = pickUpRow(id, aliceKeys.publicKey, bob.edPublicKey(), Array.empty)
      .copy(requestType = ShareRequestType.Delete)
    val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
    relay.pending = List(forged)

    assertEquals(svc.listPendingRequests(), List.empty)
  }

  test("respond throws SignatureVerificationException when senderSignature doesn't verify") {
    val relay = FakeShareRelay()
    val (svc, bob, _) = newService(relay)
    val id = UUID.randomUUID()
    val unsigned = pickUpRow(id, aliceKeys.publicKey, bob.edPublicKey(), Array.empty)
      .copy(requestType = ShareRequestType.Delete)
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
    val svc = ShareService(
      relayResolver = FixedShareRelayResolver(relay),
      encryption = NoOpShareEncryption,
      shareRepository = shareRepo,
      shareMetadataRepository = metaRepo,
      secretRepository = secretRepo,
      contactRepository = FakeContactRepository(List(aliceContact)),
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
    val canon = PayloadCanonical.forOpen(secretId, ShareRequestType.RecoveryMetadata, recipientKey, label, createdAt, None, None, Some(k), Some(n))
    val sig = signer.sign(canon)
    val now = Instant.now()
    ShareRequest(
      id = UUID.randomUUID(),
      secretId = secretId,
      senderKey = senderKey,
      recipientKey = recipientKey,
      label = label,
      secretCreatedAt = createdAt,
      requestType = ShareRequestType.RecoveryMetadata,
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
    assertEquals(opened.requestType, ShareRequestType.RecoveryMetadata)
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
