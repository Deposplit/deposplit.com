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

// ---------------------------------------------------------------------------
// In-memory test double — no mocking framework needed.
// PublicKey equality uses toBase64Url since Array[Byte] lacks value equality.
// ---------------------------------------------------------------------------
class InMemoryShareRepository extends ShareRepository:

  private var shares: Seq[Share]       = Seq.empty
  private var requests: Seq[ShareRequest] = Seq.empty

  private def sameKey(a: PublicKey, b: PublicKey): Boolean =
    a.toBase64Url == b.toBase64Url

  // --- Shares ---

  override def saveShare(share: Share): Unit =
    shares = shares :+ share

  override def getShareById(id: UUID): Option[Share] =
    shares.find(_.id == id)

  override def getShare(secretId: SecretId, senderKey: PublicKey, recipientKey: PublicKey): Option[Share] =
    shares.find(s =>
      s.secretId == secretId &&
        sameKey(s.senderKey, senderKey) &&
        sameKey(s.recipientKey, recipientKey)
    )

  override def getSharesAsSender(senderKey: PublicKey, counterpartyKey: Option[PublicKey]): Seq[ShareMetadata] =
    shares
      .filter(s =>
        sameKey(s.senderKey, senderKey) &&
          counterpartyKey.forall(ck => sameKey(s.recipientKey, ck))
      )
      .map(s => ShareMetadata(s.id, s.secretId, s.senderKey, s.recipientKey, s.label, s.createdAt))

  override def getSharesAsRecipient(recipientKey: PublicKey, counterpartyKey: Option[PublicKey]): Seq[ShareMetadata] =
    shares
      .filter(s =>
        sameKey(s.recipientKey, recipientKey) &&
          counterpartyKey.forall(ck => sameKey(s.senderKey, ck))
      )
      .map(s => ShareMetadata(s.id, s.secretId, s.senderKey, s.recipientKey, s.label, s.createdAt))

  override def deleteShares(recipientKey: PublicKey, senderKey: Option[PublicKey], secretId: Option[SecretId]): Unit =
    shares = shares.filterNot(s =>
      sameKey(s.recipientKey, recipientKey) &&
        senderKey.forall(sk => sameKey(s.senderKey, sk)) &&
        secretId.forall(sid => s.secretId == sid)
    )

  // --- Share requests ---

  override def saveShareRequest(request: ShareRequest): Unit =
    requests = requests :+ request

  override def getShareRequestById(requestId: UUID): Option[ShareRequest] =
    requests.find(_.id == requestId)

  override def getShareRequestsAsSender(senderKey: PublicKey, state: Option[ShareRequestState]): Seq[ShareRequest] =
    requests.filter(r =>
      sameKey(r.share.senderKey, senderKey) &&
        state.forall(_ == r.state)
    )

  override def getShareRequestsAsRecipient(recipientKey: PublicKey, state: Option[ShareRequestState]): Seq[ShareRequest] =
    requests.filter(r =>
      sameKey(r.share.recipientKey, recipientKey) &&
        state.forall(_ == r.state)
    )

  override def hasPendingRequest(shareId: UUID, requestType: ShareRequestType): Boolean =
    requests.exists(r =>
      r.share.id == shareId &&
        r.requestType == requestType &&
        r.state == ShareRequestState.Pending
    )

  override def updateShareRequest(requestId: UUID, state: ShareRequestState, respondedAt: Instant): Unit =
    requests = requests.map(r =>
      if r.id == requestId then r.copy(state = state, respondedAt = Some(respondedAt)) else r
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

  private def newService(): (InMemoryShareRepository, SharesService) =
    val repo = InMemoryShareRepository()
    (repo, SharesService(repo))

  // --- depositShare ---

  test("depositShare stores the share and returns Right with metadata") {
    val (_, service) = newService()
    val secretId     = freshSecretId()
    val result       = service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    assert(result.isRight)
    assertEquals(result.getOrElse(fail("not right")).secretId, secretId)
  }

  test("depositShare rejects a duplicate (same secretId, sender, recipient)") {
    val (_, service) = newService()
    val secretId     = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val result = service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    assertEquals(result, Left(Error.Conflict))
  }

  // --- listShares ---

  test("listShares as sender returns metadata for the correct pair") {
    val (_, service) = newService()
    val secretId     = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val result = service.listShares(alice, asSender = true, counterpartyKey = Some(bob))
    assert(result.isRight)
    assertEquals(result.getOrElse(Seq.empty).map(_.secretId), Seq(secretId))
  }

  test("listShares as sender does not leak shares belonging to a different pair") {
    val (_, service) = newService()
    service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
    val result = service.listShares(alice, asSender = true, counterpartyKey = Some(charlie))
    assertEquals(result, Right(Seq.empty))
  }

  test("listShares as recipient returns shares held by the caller") {
    val (_, service) = newService()
    val secretId     = freshSecretId()
    service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
    val result = service.listShares(bob, asSender = false, counterpartyKey = None)
    assert(result.isRight)
    assertEquals(result.getOrElse(Seq.empty).map(_.secretId), Seq(secretId))
  }

  // --- openShareRequest ---

  test("openShareRequest returns a request when the share exists and caller is sender") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val result = service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
    assert(result.isRight)
  }

  test("openShareRequest returns NotFound when the share does not exist") {
    val (_, service) = newService()
    assertEquals(
      service.openShareRequest(alice, UUID.randomUUID(), ShareRequestType.Retrieve),
      Left(Error.NotFound)
    )
  }

  test("openShareRequest returns Forbidden when the caller is not the sender") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    assertEquals(
      service.openShareRequest(charlie, shareId, ShareRequestType.Retrieve),
      Left(Error.Forbidden)
    )
  }

  test("openShareRequest returns Conflict when a pending request of the same type already exists") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
    assertEquals(
      service.openShareRequest(alice, shareId, ShareRequestType.Retrieve),
      Left(Error.Conflict)
    )
  }

  test("openShareRequest allows a second pending request of a different type") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
    assert(service.openShareRequest(alice, shareId, ShareRequestType.Delete).isRight)
  }

  // --- respondToShareRequest (Retrieve) ---

  test("respondToShareRequest approve Retrieve returns ciphertext and marks Approved") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val requestId    = service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
      .getOrElse(fail("request failed")).id
    val result       = service.respondToShareRequest(bob, requestId, approved = true)
    assert(result.isRight)
    val req = result.getOrElse(fail("no result"))
    assertEquals(req.state, ShareRequestState.Approved)
    assert(java.util.Arrays.equals(req.ciphertext.getOrElse(Array.empty[Byte]), ciphertext))
  }

  test("respondToShareRequest returns NotFound for an unknown request") {
    val (_, service) = newService()
    assertEquals(
      service.respondToShareRequest(bob, UUID.randomUUID(), approved = true),
      Left(Error.NotFound)
    )
  }

  test("respondToShareRequest returns Forbidden when caller is not the recipient") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val requestId    = service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
      .getOrElse(fail("request failed")).id
    assertEquals(
      service.respondToShareRequest(charlie, requestId, approved = true),
      Left(Error.Forbidden)
    )
  }

  test("respondToShareRequest returns Conflict when the request is not Pending") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val requestId    = service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
      .getOrElse(fail("request failed")).id
    service.respondToShareRequest(bob, requestId, approved = true)
    assertEquals(
      service.respondToShareRequest(bob, requestId, approved = true),
      Left(Error.Conflict)
    )
  }

  // --- respondToShareRequest (Deny) ---

  test("respondToShareRequest deny marks the request Denied with no ciphertext") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val requestId    = service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
      .getOrElse(fail("request failed")).id
    val result       = service.respondToShareRequest(bob, requestId, approved = false)
    assert(result.isRight)
    val req = result.getOrElse(fail("no result"))
    assertEquals(req.state, ShareRequestState.Denied)
    assertEquals(req.ciphertext, None)
  }

  test("respondToShareRequest deny on already-processed request returns Conflict") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val requestId    = service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
      .getOrElse(fail("request failed")).id
    service.respondToShareRequest(bob, requestId, approved = false)
    assertEquals(
      service.respondToShareRequest(bob, requestId, approved = false),
      Left(Error.Conflict)
    )
  }

  // --- respondToShareRequest (Delete) ---

  test("respondToShareRequest approve Delete removes the targeted share") {
    val (repo, service) = newService()
    val shareId         = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val requestId       = service.openShareRequest(alice, shareId, ShareRequestType.Delete)
      .getOrElse(fail("request failed")).id
    assert(service.respondToShareRequest(bob, requestId, approved = true).isRight)
    assertEquals(repo.getShareById(shareId), None)
  }

  // --- getShareRequest ---

  test("getShareRequest returns the request for the sender") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val requestId    = service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
      .getOrElse(fail("request failed")).id
    assert(service.getShareRequest(alice, requestId).isRight)
  }

  test("getShareRequest returns the request for the recipient") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val requestId    = service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
      .getOrElse(fail("request failed")).id
    assert(service.getShareRequest(bob, requestId).isRight)
  }

  test("getShareRequest returns Forbidden for an unrelated caller") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    val requestId    = service.openShareRequest(alice, shareId, ShareRequestType.Retrieve)
      .getOrElse(fail("request failed")).id
    assertEquals(service.getShareRequest(charlie, requestId), Left(Error.Forbidden))
  }

  // --- deleteShareById ---

  test("deleteShareById removes the share for the authenticated recipient") {
    val (repo, service) = newService()
    val shareId         = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    assertEquals(service.deleteShareById(bob, shareId), Right(()))
    assertEquals(repo.getShareById(shareId), None)
  }

  test("deleteShareById returns Forbidden when caller is not the recipient") {
    val (_, service) = newService()
    val shareId      = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    assertEquals(service.deleteShareById(charlie, shareId), Left(Error.Forbidden))
  }

  test("deleteShareById returns NotFound for an unknown share") {
    val (_, service) = newService()
    assertEquals(service.deleteShareById(bob, UUID.randomUUID()), Left(Error.NotFound))
  }

  // --- deleteShares (recipient-initiated bulk deletion) ---

  test("deleteShares removes a specific share without consent") {
    val (repo, service) = newService()
    val secretId        = freshSecretId()
    val shareId         = service.depositShare(alice, bob, secretId, freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    assertEquals(service.deleteShares(bob, Some(alice), Some(secretId)), Right(()))
    assertEquals(repo.getShareById(shareId), None)
  }

  test("deleteShares removes all shares from a given sender") {
    val (repo, service) = newService()
    service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
    service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
    assertEquals(service.deleteShares(bob, Some(alice), None), Right(()))
    assertEquals(repo.getSharesAsSender(alice, Some(bob)), Seq.empty)
  }

  test("deleteShares does not remove shares held for a different sender") {
    val (repo, service) = newService()
    val shareId         = service.depositShare(alice, bob, freshSecretId(), freshLabel(), Instant.now(), ciphertext)
      .getOrElse(fail("deposit failed")).id
    service.deleteShares(bob, Some(charlie), None)
    assert(repo.getShareById(shareId).isDefined)
  }
