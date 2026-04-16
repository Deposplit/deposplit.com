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
import java.util.{Base64, UUID}
import scala.collection.mutable

// ---------------------------------------------------------------------------
// In-memory test double — no mocking framework needed.
// PublicKey equality uses toBase64Url since Array[Byte] lacks value equality.
// ---------------------------------------------------------------------------
class InMemoryShareRepository extends ShareRepository:

  private var shares: Seq[Share] = Seq.empty
  private var requests: Seq[ShareRequest] = Seq.empty

  private def sameKey(a: PublicKey, b: PublicKey): Boolean =
    a.toBase64Url == b.toBase64Url

  override def saveShare(share: Share): Unit =
    shares = shares :+ share

  override def getShareMetadata(senderKey: PublicKey, recipientKey: PublicKey): Seq[ShareMetadata] =
    shares
      .filter(s => sameKey(s.senderKey, senderKey) && sameKey(s.recipientKey, recipientKey))
      .map(s => ShareMetadata(s.secretId, s.label, s.createdAt))

  override def getShare(secretId: SecretId, senderKey: PublicKey, recipientKey: PublicKey): Option[Share] =
    shares.find(s =>
      s.secretId == secretId &&
        sameKey(s.senderKey, senderKey) &&
        sameKey(s.recipientKey, recipientKey)
    )

  override def deleteShares(recipientKey: PublicKey, senderKey: Option[PublicKey], secretId: Option[SecretId]): Unit =
    shares = shares.filterNot(s =>
      sameKey(s.recipientKey, recipientKey) &&
        senderKey.forall(sk => sameKey(s.senderKey, sk)) &&
        secretId.forall(sid => s.secretId == sid)
    )

  override def saveShareRequest(request: ShareRequest): Unit =
    requests = requests :+ request

  override def getShareRequest(requestId: UUID, recipientKey: PublicKey): Option[ShareRequest] =
    requests.find(r => r.id == requestId && sameKey(r.recipientKey, recipientKey))

  override def getPendingShareRequests(recipientKey: PublicKey): Seq[ShareRequest] =
    requests.filter(r => sameKey(r.recipientKey, recipientKey) && r.state == ShareRequestState.Pending)

  override def updateShareRequestState(requestId: UUID, state: ShareRequestState): Unit =
    requests = requests.map(r => if r.id == requestId then r.copy(state = state) else r)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
object Fixtures:
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def makeKey(seed: Byte): PublicKey =
    val b64 = encoder.encodeToString(Array.fill(32)(seed))
    PublicKey.fromBase64Url(b64).getOrElse(throw IllegalStateException("fixture setup"))

  val alice: PublicKey = makeKey(0x01)
  val bob: PublicKey = makeKey(0x02)
  val charlie: PublicKey = makeKey(0x03)
  val ciphertext: Array[Byte] = Array.fill(64)(0xab.toByte)

  def freshSecretId(): SecretId = SecretId.random()
  def freshLabel(): Label = Label("test secret")

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
class SharesServiceTests extends munit.FunSuite:

  import Fixtures.*

  // Each test gets a fresh repository and service.
  private def newService(): (InMemoryShareRepository, SharesService) =
    val repo = InMemoryShareRepository()
    val service = SharesService(repo)
    (repo, service)

  // --- depositShare ---

  test("depositShare stores the share and returns Right") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val result = service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    assertEquals(result, Right(()))
  }

  test("depositShare rejects a duplicate (same secretId, sender, recipient)") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val result = service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    assertEquals(result, Left(Error.Conflict))
  }

  // --- listShares ---

  test("listShares returns metadata for the correct sender/recipient pair") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    val label = freshLabel()
    service.depositShare(alice, bob, secretId, label, Instant.now(), ciphertext)
    val result = service.listShares(alice, bob)
    assert(result.isRight)
    assertEquals(result.getOrElse(Seq.empty).map(_.secretId), Seq(secretId))
  }

  test("listShares does not leak shares belonging to a different pair") {
    val (_, service) = newService()
    service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
    val result = service.listShares(alice, charlie) // charlie holds nothing for alice
    assertEquals(result, Right(Seq.empty))
  }

  // --- requestRetrieval ---

  test("requestRetrieval returns a request UUID when the share exists") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val result = service.requestRetrieval(alice, bob, secretId)
    assert(result.isRight)
  }

  test("requestRetrieval returns NotFound when the share does not exist") {
    val (_, service) = newService()
    val result = service.requestRetrieval(alice, bob, freshSecretId())
    assertEquals(result, Left(Error.NotFound))
  }

  // --- approveRetrieval ---

  test("approveRetrieval returns the ciphertext and marks the request Approved") {
    val (repo, service) = newService()
    val secretId = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val requestId = service.requestRetrieval(alice, bob, secretId).getOrElse(fail("no request id"))
    val result = service.approveRetrieval(bob, requestId)
    assert(result.isRight)
    assert(java.util.Arrays.equals(result.getOrElse(Array.empty[Byte]), ciphertext))
    val req = repo.getShareRequest(requestId, bob).getOrElse(fail("request gone"))
    assertEquals(req.state, ShareRequestState.Approved)
  }

  test("approveRetrieval returns NotFound for an unknown request") {
    val (_, service) = newService()
    assertEquals(service.approveRetrieval(bob, UUID.randomUUID()), Left(Error.NotFound))
  }

  test("approveRetrieval returns NotFound when the request is not Pending") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val requestId = service.requestRetrieval(alice, bob, secretId).getOrElse(fail("no request id"))
    service.approveRetrieval(bob, requestId) // first approval
    val result = service.approveRetrieval(bob, requestId) // attempt to approve again
    assertEquals(result, Left(Error.NotFound))
  }

  test("approveRetrieval returns NotFound for a Delete request") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val requestId = service.requestDeletion(alice, bob, Some(secretId)).getOrElse(fail("no request id"))
    val result = service.approveRetrieval(bob, requestId)
    assertEquals(result, Left(Error.NotFound))
  }

  // --- denyRequest ---

  test("denyRequest marks the request Denied") {
    val (repo, service) = newService()
    val secretId = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val requestId = service.requestRetrieval(alice, bob, secretId).getOrElse(fail("no request id"))
    assertEquals(service.denyRequest(bob, requestId), Right(()))
    val req = repo.getShareRequest(requestId, bob).getOrElse(fail("request gone"))
    assertEquals(req.state, ShareRequestState.Denied)
  }

  test("denyRequest returns NotFound for an already-processed request") {
    val (_, service) = newService()
    val secretId = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val requestId = service.requestRetrieval(alice, bob, secretId).getOrElse(fail("no request id"))
    service.denyRequest(bob, requestId)
    assertEquals(service.denyRequest(bob, requestId), Left(Error.NotFound))
  }

  // --- approveDeletion ---

  test("approveDeletion removes the targeted share") {
    val (repo, service) = newService()
    val secretId = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val requestId = service.requestDeletion(alice, bob, Some(secretId)).getOrElse(fail("no request id"))
    assertEquals(service.approveDeletion(bob, requestId), Right(()))
    assertEquals(repo.getShare(secretId, alice, bob), None)
  }

  test("approveDeletion with no secretId removes all shares from the sender") {
    val (repo, service) = newService()
    val sid1 = freshSecretId()
    val sid2 = freshSecretId()
    service.depositShare(alice, bob, sid1, freshLabel(), Instant.now(), ciphertext)
    service.depositShare(alice, bob, sid2, freshLabel(), Instant.now(), ciphertext)
    val requestId = service.requestDeletion(alice, bob, None).getOrElse(fail("no request id"))
    assertEquals(service.approveDeletion(bob, requestId), Right(()))
    assertEquals(repo.getShareMetadata(alice, bob), Seq.empty)
  }

  // --- deleteShares (recipient-initiated, unilateral) ---

  test("deleteShares removes a specific share without consent") {
    val (repo, service) = newService()
    val secretId = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    assertEquals(service.deleteShares(bob, Some(alice), Some(secretId)), Right(()))
    assertEquals(repo.getShare(secretId, alice, bob), None)
  }

  test("deleteShares removes all shares from a given sender") {
    val (repo, service) = newService()
    service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
    service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
    assertEquals(service.deleteShares(bob, Some(alice), None), Right(()))
    assertEquals(repo.getShareMetadata(alice, bob), Seq.empty)
  }

  test("deleteShares does not remove shares held for a different sender") {
    val (repo, service) = newService()
    val sid = freshSecretId()
    service.depositShare(alice, bob, sid, freshLabel(), Instant.now(), ciphertext)
    service.deleteShares(bob, Some(charlie), None) // charlie has no shares with bob
    assert(repo.getShare(sid, alice, bob).isDefined)
  }
