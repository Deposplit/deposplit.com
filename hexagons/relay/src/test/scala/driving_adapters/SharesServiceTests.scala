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

import driven_ports.persistence.ShareRepository
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import value_objects.*

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

// ---------------------------------------------------------------------------
// In-memory test double — no mocking framework needed.
// PublicKey equality uses toBase64Url since Array[Byte] lacks value equality.
// ---------------------------------------------------------------------------
class InMemoryShareRepository extends ShareRepository:

  private var requests: Seq[ShareRequest] = Seq.empty

  private def sameKey(a: PublicKey, b: PublicKey): Boolean =
    a.toBase64Url == b.toBase64Url

  override def saveShareRequest(request: ShareRequest): Unit =
    requests = requests :+ request

  override def getShareRequestById(id: UUID): Option[ShareRequest] =
    requests.find(_.id == id)

  override def getShareRequestsAsSender(
      senderKey: PublicKey,
      requestType: Option[ShareRequestType],
      state: Option[ShareRequestState]
  ): Seq[ShareRequest] =
    requests.filter(r =>
      sameKey(r.senderKey, senderKey) &&
        requestType.forall(_ == r.requestType) &&
        state.forall(_ == r.state)
    )

  override def getShareRequestsAsRecipient(
      recipientKey: PublicKey,
      requestType: Option[ShareRequestType],
      state: Option[ShareRequestState]
  ): Seq[ShareRequest] =
    requests.filter(r =>
      sameKey(r.recipientKey, recipientKey) &&
        requestType.forall(_ == r.requestType) &&
        state.forall(_ == r.state)
    )

  override def hasActivePickUp(secretId: SecretId, recipientKey: PublicKey): Boolean =
    requests.exists(r =>
      r.secretId == secretId &&
        sameKey(r.recipientKey, recipientKey) &&
        r.requestType == ShareRequestType.PickUp &&
        r.state != ShareRequestState.Denied
    )

  override def hasPendingRequest(
      secretId: SecretId,
      senderKey: PublicKey,
      recipientKey: PublicKey,
      requestType: ShareRequestType
  ): Boolean =
    requests.exists(r =>
      r.secretId == secretId &&
        sameKey(r.senderKey, senderKey) &&
        sameKey(r.recipientKey, recipientKey) &&
        r.requestType == requestType &&
        r.state == ShareRequestState.Pending
    )

  override def updateShareRequest(
      requestId: UUID,
      state: ShareRequestState,
      respondedAt: Instant,
      ciphertext: Option[Array[Byte]],
      recipientSignature: Signature
  ): Unit =
    requests = requests.map(r =>
      if r.id == requestId then
        r.copy(state = state, respondedAt = Some(respondedAt), ciphertext = ciphertext, recipientSignature = Some(recipientSignature))
      else r
    )

  override def deleteShareRequestById(id: UUID): Unit =
    requests = requests.filterNot(_.id == id)

  override def deleteShareRequests(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Unit =
    requests = requests.filterNot(r =>
      sameKey(r.recipientKey, recipientKey) &&
        senderKey.forall(sk => sameKey(r.senderKey, sk)) &&
        secretId.forall(sid => r.secretId == sid)
    )

// ---------------------------------------------------------------------------
// Fixtures — real Ed25519 keypairs, since senderSignature/recipientSignature now
// require a genuinely valid signature, not just a well-formed public key.
// ---------------------------------------------------------------------------
object Fixtures:
  private val encoder = Base64.getUrlEncoder.withoutPadding

  final case class KeyPair(publicKey: PublicKey, privateKeyBytes: Array[Byte]):
    def sign(bytes: Array[Byte]): Signature =
      val signer = Ed25519Signer()
      signer.init(true, Ed25519PrivateKeyParameters(privateKeyBytes, 0))
      signer.update(bytes, 0, bytes.length)
      Signature.fromBytes(signer.generateSignature()).getOrElse(throw IllegalStateException("sign failed"))

  def generateKeyPair(): KeyPair =
    val gen = Ed25519KeyPairGenerator()
    gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
    val pair = gen.generateKeyPair()
    val pubBytes = pair.getPublic.asInstanceOf[Ed25519PublicKeyParameters].getEncoded
    val privBytes = pair.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters].getEncoded
    val publicKey = PublicKey.fromBytes(pubBytes).getOrElse(throw IllegalStateException("fixture setup"))
    KeyPair(publicKey, privBytes)

  val aliceKeys: KeyPair = generateKeyPair()
  val bobKeys: KeyPair = generateKeyPair()
  val charlieKeys: KeyPair = generateKeyPair()

  val alice: PublicKey = aliceKeys.publicKey
  val bob: PublicKey = bobKeys.publicKey
  val charlie: PublicKey = charlieKeys.publicKey
  val ciphertext: Array[Byte] = Array.fill(64)(0xab.toByte)

  def signerFor(pk: PublicKey): KeyPair =
    Seq(aliceKeys, bobKeys, charlieKeys).find(_.publicKey.toBase64Url == pk.toBase64Url).getOrElse(
      throw IllegalArgumentException("no fixture keypair for this public key")
    )

  def freshSecretId(): SecretId = SecretId.random()
  def freshLabel(): Label = Label("test secret")

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
class SharesServiceTests extends munit.FunSuite:

  import Fixtures.*

  private def newService(): (InMemoryShareRepository, ShareRequestsService) =
    val repo = InMemoryShareRepository()
    (repo, ShareRequestsService(repo))

  /** Signs and opens a share request — the signing counterpart of `service.openShareRequest`
    * used throughout these tests, since a genuinely valid Ed25519 signature is now required.
    */
  private def open(
      service: ShareRequestsService,
      sender: PublicKey,
      recipient: PublicKey,
      secretId: SecretId,
      label: Label,
      secretCreatedAt: Instant,
      requestType: ShareRequestType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]]
  ): Either[Error, ShareRequest] =
    val sig = signerFor(sender).sign(
      PayloadCanonical.forOpen(secretId, requestType, recipient, label, secretCreatedAt, shareId, ciphertext)
    )
    service.openShareRequest(sender, recipient, secretId, label, secretCreatedAt, requestType, shareId, ciphertext, sig)

  /** Signs and responds — the signing counterpart of `service.respondToShareRequest`. */
  private def respond(
      service: ShareRequestsService,
      recipient: PublicKey,
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]] = None
  ): Either[Error, ShareRequest] =
    val sig = signerFor(recipient).sign(PayloadCanonical.forRespond(requestId, approved, ciphertext))
    service.respondToShareRequest(recipient, requestId, approved, ciphertext, sig)

  private def deposit(
      service: ShareRequestsService,
      sender: PublicKey = alice,
      recipient: PublicKey = bob
  ): ShareRequest =
    open(
      service,
      sender,
      recipient,
      freshSecretId(),
      freshLabel(),
      Instant.now(),
      ShareRequestType.PickUp,
      None,
      Some(ciphertext)
    ).getOrElse(fail("deposit failed"))

  // --- openShareRequest (PickUp) ---

  test("PickUp stores the request and returns Right") {
    val (_, service) = newService()
    val result = open(service, alice, bob, freshSecretId(), freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    assert(result.isRight)
    assertEquals(result.getOrElse(fail("not right")).requestType, ShareRequestType.PickUp)
  }

  test("PickUp returns BadRequest when ciphertext is absent") {
    val (_, service) = newService()
    val result = open(service, alice, bob, freshSecretId(), freshLabel(), Instant.now(), ShareRequestType.PickUp, None, None)
    assertEquals(result, Left(Error.BadRequest))
  }

  test("openShareRequest returns BadRequest when senderSignature doesn't verify") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val label = freshLabel()
    val createdAt = Instant.now()
    // Signed by bob's key instead of the caller's (alice) — will not verify against alice.
    val wrongSig = bobKeys.sign(PayloadCanonical.forOpen(secretId, ShareRequestType.PickUp, bob, label, createdAt, None, Some(ciphertext)))
    val result = service.openShareRequest(alice, bob, secretId, label, createdAt, ShareRequestType.PickUp, None, Some(ciphertext), wrongSig)
    assertEquals(result, Left(Error.BadRequest))
  }

  test("openShareRequest returns BadRequest when senderSignature covers tampered fields") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val label = freshLabel()
    val createdAt = Instant.now()
    // Valid signature over a different label than what's actually submitted.
    val sig = aliceKeys.sign(PayloadCanonical.forOpen(secretId, ShareRequestType.PickUp, bob, Label("other label"), createdAt, None, Some(ciphertext)))
    val result = service.openShareRequest(alice, bob, secretId, label, createdAt, ShareRequestType.PickUp, None, Some(ciphertext), sig)
    assertEquals(result, Left(Error.BadRequest))
  }

  test("PickUp returns Conflict when an active PickUp already exists (pending)") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    val result = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    assertEquals(result, Left(Error.Conflict))
  }

  test("PickUp returns Conflict when an active PickUp already exists (approved)") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val req = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    respond(service, bob, req.id, approved = true)
    val result = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    assertEquals(result, Left(Error.Conflict))
  }

  test("PickUp allows re-deposit after denial") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val req = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    respond(service, bob, req.id, approved = false)
    val result = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    assert(result.isRight)
  }

  // --- respondToShareRequest (PickUp) ---

  test("Approving a PickUp delivers and clears ciphertext") {
    val (repo, service) = newService()
    val req = deposit(service)
    val result = respond(service, bob, req.id, approved = true)
    assert(result.isRight)
    val responded = result.getOrElse(fail("not right"))
    assertEquals(responded.state, ShareRequestState.Approved)
    assert(java.util.Arrays.equals(responded.ciphertext.getOrElse(Array.empty[Byte]), ciphertext))
    assert(responded.recipientSignature.isDefined)
    // Relay clears the ciphertext
    assertEquals(repo.getShareRequestById(req.id).flatMap(_.ciphertext), None)
  }

  test("Denying a PickUp clears ciphertext and marks Denied") {
    val (repo, service) = newService()
    val req = deposit(service)
    val result = respond(service, bob, req.id, approved = false)
    assert(result.isRight)
    assertEquals(result.getOrElse(fail("not right")).state, ShareRequestState.Denied)
    assertEquals(repo.getShareRequestById(req.id).flatMap(_.ciphertext), None)
  }

  test("respondToShareRequest PickUp returns NotFound for unknown id") {
    val (_, service) = newService()
    assertEquals(respond(service, bob, UUID.randomUUID(), approved = true), Left(Error.NotFound))
  }

  test("respondToShareRequest PickUp returns Forbidden when caller is not the recipient") {
    val (_, service) = newService()
    val req = deposit(service)
    assertEquals(respond(service, charlie, req.id, approved = true), Left(Error.Forbidden))
  }

  test("respondToShareRequest returns Conflict when request is not Pending") {
    val (_, service) = newService()
    val req = deposit(service)
    respond(service, bob, req.id, approved = true)
    assertEquals(respond(service, bob, req.id, approved = true), Left(Error.Conflict))
  }

  test("respondToShareRequest returns BadRequest when recipientSignature doesn't verify") {
    val (_, service) = newService()
    val req = deposit(service)
    // Signed by charlie instead of the caller (bob).
    val wrongSig = charlieKeys.sign(PayloadCanonical.forRespond(req.id, approved = true, None))
    val result = service.respondToShareRequest(bob, req.id, approved = true, None, wrongSig)
    assertEquals(result, Left(Error.BadRequest))
  }

  test("respondToShareRequest returns BadRequest when recipientSignature covers a different decision") {
    val (_, service) = newService()
    val req = deposit(service)
    // Validly signed by bob, but over "denied" while the call claims "approved".
    val sig = bobKeys.sign(PayloadCanonical.forRespond(req.id, approved = false, None))
    val result = service.respondToShareRequest(bob, req.id, approved = true, None, sig)
    assertEquals(result, Left(Error.BadRequest))
  }

  // --- openShareRequest (Retrieve / Delete) ---

  test("Retrieve request returns Conflict when a pending one already exists") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUp = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUp.id), None)
    val result = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUp.id), None)
    assertEquals(result, Left(Error.Conflict))
  }

  test("Retrieve and Delete requests can coexist as pending") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUp = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUp.id), None)
    assert(
      open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Delete, Some(pickUp.id), None).isRight
    )
  }

  // --- respondToShareRequest (Retrieve) ---

  test("Approving Retrieve stores and returns the ciphertext") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUpReq = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val retrieveReq = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUpReq.id), None)
      .getOrElse(fail("retrieve request failed"))
    val result = respond(service, bob, retrieveReq.id, approved = true, Some(ciphertext))
    assert(result.isRight)
    val responded = result.getOrElse(fail("not right"))
    assertEquals(responded.state, ShareRequestState.Approved)
    assert(java.util.Arrays.equals(responded.ciphertext.getOrElse(Array.empty[Byte]), ciphertext))
  }

  test("Approving Retrieve without ciphertext returns BadRequest") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUpReq = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val retrieveReq = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUpReq.id), None)
      .getOrElse(fail("retrieve request failed"))
    assertEquals(respond(service, bob, retrieveReq.id, approved = true, None), Left(Error.BadRequest))
  }

  test("Denying Retrieve marks Denied with no ciphertext") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUpReq = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val retrieveReq = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUpReq.id), None)
      .getOrElse(fail("retrieve request failed"))
    val result = respond(service, bob, retrieveReq.id, approved = false)
    assert(result.isRight)
    assertEquals(result.getOrElse(fail("not right")).state, ShareRequestState.Denied)
    assertEquals(result.getOrElse(fail("not right")).ciphertext, None)
  }

  // --- respondToShareRequest (Delete) ---

  test("Approving Delete removes all rows for that (secretId, senderKey, recipientKey)") {
    val (repo, service) = newService()
    val secretId = freshSecretId()
    val pickUpReq = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val deleteReq = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Delete, Some(pickUpReq.id), None)
      .getOrElse(fail("delete request failed"))
    assert(respond(service, bob, deleteReq.id, approved = true).isRight)
    assertEquals(repo.getShareRequestById(pickUpReq.id), None)
    assertEquals(repo.getShareRequestById(deleteReq.id), None)
  }

  // --- getShareRequest ---

  test("getShareRequest returns the request for the sender") {
    val (_, service) = newService()
    val req = deposit(service)
    assert(service.getShareRequest(alice, req.id).isRight)
  }

  test("getShareRequest returns the request for the recipient") {
    val (_, service) = newService()
    val req = deposit(service)
    assert(service.getShareRequest(bob, req.id).isRight)
  }

  test("getShareRequest returns Forbidden for an unrelated caller") {
    val (_, service) = newService()
    val req = deposit(service)
    assertEquals(service.getShareRequest(charlie, req.id), Left(Error.Forbidden))
  }

  test("getShareRequest returns NotFound for unknown id") {
    val (_, service) = newService()
    assertEquals(service.getShareRequest(alice, UUID.randomUUID()), Left(Error.NotFound))
  }

  // --- listShareRequests ---

  test("listShareRequests as sender returns PickUp requests deposited by caller") {
    val (_, service) = newService()
    val req = deposit(service)
    val result = service.listShareRequests(alice, asSender = true, Some(ShareRequestType.PickUp), None)
    assert(result.isRight)
    assertEquals(result.getOrElse(Seq.empty).map(_.id), Seq(req.id))
  }

  test("listShareRequests as recipient returns PickUp requests directed at caller") {
    val (_, service) = newService()
    deposit(service)
    val result = service.listShareRequests(bob, asSender = false, Some(ShareRequestType.PickUp), None)
    assert(result.isRight)
    assertEquals(result.getOrElse(Seq.empty).size, 1)
  }

  test("listShareRequests filters by state correctly") {
    val (_, service) = newService()
    val req = deposit(service)
    respond(service, bob, req.id, approved = true)
    val pending = service.listShareRequests(alice, asSender = true, None, Some(ShareRequestState.Pending))
    val approved = service.listShareRequests(alice, asSender = true, None, Some(ShareRequestState.Approved))
    assertEquals(pending.getOrElse(Seq.empty).size, 0)
    assertEquals(approved.getOrElse(Seq.empty).size, 1)
  }

  // --- deleteShareRequestById ---

  test("deleteShareRequestById removes the row for the authenticated recipient") {
    val (repo, service) = newService()
    val req = deposit(service)
    assertEquals(service.deleteShareRequestById(bob, req.id), Right(()))
    assertEquals(repo.getShareRequestById(req.id), None)
  }

  test("deleteShareRequestById cascades Retrieve/Delete rows when deleting a PickUp") {
    val (repo, service) = newService()
    val secretId = freshSecretId()
    val pickUp = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val retrieve = open(service, alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUp.id), None)
      .getOrElse(fail("retrieve request failed"))
    service.deleteShareRequestById(alice, pickUp.id)
    assertEquals(repo.getShareRequestById(pickUp.id), None)
    assertEquals(repo.getShareRequestById(retrieve.id), None)
  }

  test("deleteShareRequestById returns Forbidden when caller is not sender or recipient") {
    val (_, service) = newService()
    val req = deposit(service)
    assertEquals(service.deleteShareRequestById(charlie, req.id), Left(Error.Forbidden))
  }

  test("deleteShareRequestById returns NotFound for unknown id") {
    val (_, service) = newService()
    assertEquals(service.deleteShareRequestById(bob, UUID.randomUUID()), Left(Error.NotFound))
  }

  // --- deleteShareRequests (bulk recipient-initiated) ---

  test("deleteShareRequests removes all rows for the recipient") {
    val (repo, service) = newService()
    deposit(service)
    deposit(service)
    service.deleteShareRequests(bob, None, None)
    assertEquals(repo.getShareRequestsAsRecipient(bob, None, None), Seq.empty)
  }

  test("deleteShareRequests filtered by sender removes only matching rows") {
    val (repo, service) = newService()
    val req1 = deposit(service, sender = alice, recipient = bob)
    val req2 = deposit(service, sender = charlie, recipient = bob)
    service.deleteShareRequests(bob, Some(alice), None)
    assertEquals(repo.getShareRequestById(req1.id), None)
    assert(repo.getShareRequestById(req2.id).isDefined)
  }
