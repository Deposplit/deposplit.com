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

package services

import driven_ports.persistence.ShareRepository
import value_objects.*

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
      ciphertext: Option[Array[Byte]]
  ): Unit =
    requests = requests.map(r =>
      if r.id == requestId then r.copy(state = state, respondedAt = Some(respondedAt), ciphertext = ciphertext)
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
// Helpers
// ---------------------------------------------------------------------------
object Fixtures:
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def makeKey(seed: Byte): PublicKey =
    val b64 = encoder.encodeToString(Array.fill(32)(seed))
    PublicKey.fromBase64Url(b64).getOrElse(throw IllegalStateException("fixture setup"))

  val alice: PublicKey     = makeKey(0x01)
  val bob: PublicKey       = makeKey(0x02)
  val charlie: PublicKey   = makeKey(0x03)
  val ciphertext: Array[Byte] = Array.fill(64)(0xab.toByte)

  def freshSecretId(): SecretId = SecretId.random()
  def freshLabel(): Label       = Label("test secret")

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
class SharesServiceTests extends munit.FunSuite:

  import Fixtures.*

  private def newService(): (InMemoryShareRepository, ShareRequestsService) =
    val repo = InMemoryShareRepository()
    (repo, ShareRequestsService(repo))

  private def deposit(service: ShareRequestsService, sender: PublicKey = alice, recipient: PublicKey = bob): ShareRequest =
    service
      .openShareRequest(sender, recipient, freshSecretId(), freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))

  // --- openShareRequest (PickUp) ---

  test("PickUp stores the request and returns Right") {
    val (_, service) = newService()
    val result = service.openShareRequest(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    assert(result.isRight)
    assertEquals(result.getOrElse(fail("not right")).requestType, ShareRequestType.PickUp)
  }

  test("PickUp returns BadRequest when ciphertext is absent") {
    val (_, service) = newService()
    val result = service.openShareRequest(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ShareRequestType.PickUp, None, None)
    assertEquals(result, Left(Error.BadRequest))
  }

  test("PickUp returns Conflict when an active PickUp already exists (pending)") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    val result = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    assertEquals(result, Left(Error.Conflict))
  }

  test("PickUp returns Conflict when an active PickUp already exists (approved)") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val req = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    service.respondToShareRequest(bob, req.id, approved = true, None)
    val result = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    assertEquals(result, Left(Error.Conflict))
  }

  test("PickUp allows re-deposit after denial") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val req = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    service.respondToShareRequest(bob, req.id, approved = false, None)
    val result = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
    assert(result.isRight)
  }

  // --- respondToShareRequest (PickUp) ---

  test("Approving a PickUp delivers and clears ciphertext") {
    val (repo, service) = newService()
    val req = deposit(service)
    val result = service.respondToShareRequest(bob, req.id, approved = true, None)
    assert(result.isRight)
    val responded = result.getOrElse(fail("not right"))
    assertEquals(responded.state, ShareRequestState.Approved)
    assert(java.util.Arrays.equals(responded.ciphertext.getOrElse(Array.empty[Byte]), ciphertext))
    // Relay clears the ciphertext
    assertEquals(repo.getShareRequestById(req.id).flatMap(_.ciphertext), None)
  }

  test("Denying a PickUp clears ciphertext and marks Denied") {
    val (repo, service) = newService()
    val req = deposit(service)
    val result = service.respondToShareRequest(bob, req.id, approved = false, None)
    assert(result.isRight)
    assertEquals(result.getOrElse(fail("not right")).state, ShareRequestState.Denied)
    assertEquals(repo.getShareRequestById(req.id).flatMap(_.ciphertext), None)
  }

  test("respondToShareRequest PickUp returns NotFound for unknown id") {
    val (_, service) = newService()
    assertEquals(service.respondToShareRequest(bob, UUID.randomUUID(), approved = true, None), Left(Error.NotFound))
  }

  test("respondToShareRequest PickUp returns Forbidden when caller is not the recipient") {
    val (_, service) = newService()
    val req = deposit(service)
    assertEquals(service.respondToShareRequest(charlie, req.id, approved = true, None), Left(Error.Forbidden))
  }

  test("respondToShareRequest returns Conflict when request is not Pending") {
    val (_, service) = newService()
    val req = deposit(service)
    service.respondToShareRequest(bob, req.id, approved = true, None)
    assertEquals(service.respondToShareRequest(bob, req.id, approved = true, None), Left(Error.Conflict))
  }

  // --- openShareRequest (Retrieve / Delete) ---

  test("Retrieve request returns Conflict when a pending one already exists") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUp = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUp.id), None)
    val result = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUp.id), None)
    assertEquals(result, Left(Error.Conflict))
  }

  test("Retrieve and Delete requests can coexist as pending") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUp = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUp.id), None)
    assert(service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Delete, Some(pickUp.id), None).isRight)
  }

  // --- respondToShareRequest (Retrieve) ---

  test("Approving Retrieve stores and returns the ciphertext") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUpReq = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val retrieveReq = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUpReq.id), None)
      .getOrElse(fail("retrieve request failed"))
    val result = service.respondToShareRequest(bob, retrieveReq.id, approved = true, Some(ciphertext))
    assert(result.isRight)
    val responded = result.getOrElse(fail("not right"))
    assertEquals(responded.state, ShareRequestState.Approved)
    assert(java.util.Arrays.equals(responded.ciphertext.getOrElse(Array.empty[Byte]), ciphertext))
  }

  test("Approving Retrieve without ciphertext returns BadRequest") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUpReq = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val retrieveReq = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUpReq.id), None)
      .getOrElse(fail("retrieve request failed"))
    assertEquals(service.respondToShareRequest(bob, retrieveReq.id, approved = true, None), Left(Error.BadRequest))
  }

  test("Denying Retrieve marks Denied with no ciphertext") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val pickUpReq = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val retrieveReq = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUpReq.id), None)
      .getOrElse(fail("retrieve request failed"))
    val result = service.respondToShareRequest(bob, retrieveReq.id, approved = false, None)
    assert(result.isRight)
    assertEquals(result.getOrElse(fail("not right")).state, ShareRequestState.Denied)
    assertEquals(result.getOrElse(fail("not right")).ciphertext, None)
  }

  // --- respondToShareRequest (Delete) ---

  test("Approving Delete removes all rows for that (secretId, senderKey, recipientKey)") {
    val (repo, service) = newService()
    val secretId = freshSecretId()
    val pickUpReq = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val deleteReq = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Delete, Some(pickUpReq.id), None)
      .getOrElse(fail("delete request failed"))
    assert(service.respondToShareRequest(bob, deleteReq.id, approved = true, None).isRight)
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
    service.respondToShareRequest(bob, req.id, approved = true, None)
    val pending  = service.listShareRequests(alice, asSender = true, None, Some(ShareRequestState.Pending))
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
    val pickUp = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.PickUp, None, Some(ciphertext))
      .getOrElse(fail("deposit failed"))
    val retrieve = service.openShareRequest(alice, bob, secretId, freshLabel(), Instant.now(), ShareRequestType.Retrieve, Some(pickUp.id), None)
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
