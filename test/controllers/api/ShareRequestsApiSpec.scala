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

package controllers.api

import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import play.api.libs.json.*
import play.api.test.*
import play.api.test.Helpers.*

import java.util.UUID

/** Integration tests for POST/GET/PATCH/DELETE /share-requests.
  *
  * Tests run in declaration order and share a single in-memory H2 database via GuiceOneAppPerSuite. Each suite uses
  * fresh keypairs so data is naturally isolated from other suites even when the DB is shared across the test JVM.
  *
  * Every POST/PATCH body carries a `senderSignature`/`recipientSignature` computed via
  * `RequestSigner.signOpen`/`signRespond`, mirroring `PayloadCanonical` — see that object for the exact
  * canonical-bytes construction being signed here.
  */
class ShareRequestsApiSpec extends PlaySpec with GuiceOneAppPerSuite:

  private val alice = new RequestSigner()
  private val bob = new RequestSigner()
  private val secretId = UUID.randomUUID().toString
  private val createdAt = "2026-01-01T00:00:00Z"

  // Populated by tests; used by subsequent tests in declaration order.
  private var pickUpId: String = ""
  private var retrieveId: String = ""

  // ── Body builders ──────────────────────────────────────────────────────────

  private def pickUpBody(
      sender: RequestSigner,
      recipient: RequestSigner,
      sid: String = secretId,
      ct: String = "AQID",
      k: Int = 2,
      n: Int = 3
  ): Array[Byte] =
    val sig = sender.signOpen(sid, "pick_up", recipient.publicKeyHeader, "test secret", createdAt, None, Some(ct), Some(k), Some(n))
    s"""{
       |  "requestType":     "pick_up",
       |  "secretId":        "$sid",
       |  "label":           "test secret",
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "secretCreatedAt": "$createdAt",
       |  "ciphertext":      "$ct",
       |  "k":               $k,
       |  "n":               $n,
       |  "senderSignature": "$sig"
       |}""".stripMargin.getBytes("UTF-8")

  private def retrieveBody(
      shareId: String,
      recipient: RequestSigner,
      sender: RequestSigner = alice
  ): Array[Byte] =
    val sig = sender.signOpen(secretId, "retrieve", recipient.publicKeyHeader, "test secret", createdAt, Some(shareId), None)
    s"""{
       |  "requestType":     "retrieve",
       |  "secretId":        "$secretId",
       |  "label":           "test secret",
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "secretCreatedAt": "$createdAt",
       |  "shareId":         "$shareId",
       |  "senderSignature": "$sig"
       |}""".stripMargin.getBytes("UTF-8")

  private def deleteRequestBody(
      shareId: String,
      recipient: RequestSigner,
      sender: RequestSigner = alice
  ): Array[Byte] =
    val sig = sender.signOpen(secretId, "delete", recipient.publicKeyHeader, "test secret", createdAt, Some(shareId), None)
    s"""{
       |  "requestType":     "delete",
       |  "secretId":        "$secretId",
       |  "label":           "test secret",
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "secretCreatedAt": "$createdAt",
       |  "shareId":         "$shareId",
       |  "senderSignature": "$sig"
       |}""".stripMargin.getBytes("UTF-8")

  private def respondBody(signer: RequestSigner, requestId: String, approved: Boolean, ciphertext: Option[String] = None): Array[Byte] =
    val sig = signer.signRespond(requestId, approved, ciphertext)
    val ciphertextField = ciphertext.fold("")(ct => s""","ciphertext":"$ct"""")
    s"""{"state":"${if approved then "approved" else "denied"}","recipientSignature":"$sig"$ciphertextField}"""
      .getBytes("UTF-8")

  // ── PickUp (deposit) flow ──────────────────────────────────────────────────

  "POST /share-requests (PickUp)" should {

    "deposit a share and return a PickUp ShareRequest with all required fields" in {
      val result = route(app, alice.post("/share-requests", pickUpBody(alice, bob))).get
      status(result) mustBe CREATED
      contentType(result) mustBe Some("application/json")
      val json = contentAsJson(result)
      pickUpId = (json \ "id").as[String]
      pickUpId must not be empty
      (json \ "requestType").as[String] mustBe "pick_up"
      (json \ "state").as[String] mustBe "pending"
      (json \ "secretId").as[String] mustBe secretId
      (json \ "label").as[String] mustBe "test secret"
      (json \ "senderKey").as[String] mustBe alice.publicKeyHeader
      (json \ "recipientKey").as[String] mustBe bob.publicKeyHeader
      (json \ "secretCreatedAt").asOpt[String] must not be empty
      (json \ "requestedAt").asOpt[String] must not be empty
      (json \ "respondedAt").asOpt[String] mustBe None
      (json \ "shareId").asOpt[String] mustBe None
      (json \ "senderSignature").asOpt[String] must not be empty
      (json \ "recipientSignature").asOpt[String] mustBe None
      // ciphertext not returned on PickUp creation (only on approval)
      (json \ "ciphertext").asOpt[String] mustBe None
      (json \ "k").as[Int] mustBe 2
      (json \ "n").as[Int] mustBe 3
    }

    "reject a PickUp with missing k/n" in {
      val sid = UUID.randomUUID().toString
      val sig = alice.signOpen(sid, "pick_up", bob.publicKeyHeader, "x", createdAt, None, Some("AQID"))
      val body =
        s"""{"requestType":"pick_up","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a PickUp with k > n" in {
      val sid = UUID.randomUUID().toString
      val sig = alice.signOpen(sid, "pick_up", bob.publicKeyHeader, "x", createdAt, None, Some("AQID"), Some(5), Some(3))
      val body =
        s"""{"requestType":"pick_up","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","k":5,"n":3,"senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a duplicate active PickUp for the same (secretId, recipientKey)" in {
      val result = route(app, alice.post("/share-requests", pickUpBody(alice, bob))).get
      status(result) mustBe CONFLICT
    }

    "reject a PickUp without ciphertext" in {
      val sid = UUID.randomUUID().toString
      val sig = alice.signOpen(sid, "pick_up", bob.publicKeyHeader, "x", createdAt, None, None)
      val body =
        s"""{"requestType":"pick_up","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a PickUp with a senderSignature that doesn't verify" in {
      val sid = UUID.randomUUID().toString
      // Signed by bob instead of the caller (alice) — verifies against the wrong key.
      val sig = bob.signOpen(sid, "pick_up", bob.publicKeyHeader, "x", createdAt, None, Some("AQID"))
      val body =
        s"""{"requestType":"pick_up","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }
  }

  // ── List / Get ─────────────────────────────────────────────────────────────

  "GET /share-requests" should {

    "list the PickUp when queried as sender with type=pick_up" in {
      val result = route(app, alice.get("/share-requests?role=sender&type=pick_up")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr.exists(j => (j \ "id").as[String] == pickUpId) mustBe true
    }

    "list the PickUp when queried as recipient" in {
      val result = route(app, bob.get("/share-requests?role=recipient")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr.exists(j => (j \ "id").as[String] == pickUpId) mustBe true
    }

    "return an empty list for a key with no requests" in {
      val stranger = new RequestSigner()
      val result = route(app, stranger.get("/share-requests?role=recipient")).get
      status(result) mustBe OK
      contentAsJson(result).as[JsArray].value mustBe empty
    }
  }

  "GET /share-requests/:requestId" should {

    "return the PickUp to the sender" in {
      val result = route(app, alice.get(s"/share-requests/$pickUpId")).get
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "id").as[String] mustBe pickUpId
      (json \ "state").as[String] mustBe "pending"
    }

    "return the PickUp to the recipient" in {
      val result = route(app, bob.get(s"/share-requests/$pickUpId")).get
      status(result) mustBe OK
      (contentAsJson(result) \ "id").as[String] mustBe pickUpId
    }

    "reject access by an unrelated third party" in {
      val stranger = new RequestSigner()
      val result = route(app, stranger.get(s"/share-requests/$pickUpId")).get
      status(result) mustBe FORBIDDEN
    }
  }

  // ── Approve PickUp (Bob collects the share) ────────────────────────────────

  "PATCH /share-requests/:requestId (approve PickUp)" should {

    "reject a response from the sender (who is not the recipient)" in {
      // alice signs with her own key (valid w.r.t. herself) — the Forbidden check is about
      // req.recipientKey vs the caller, not about the signature itself.
      val result = route(app, alice.patch(s"/share-requests/$pickUpId", respondBody(alice, pickUpId, approved = true))).get
      status(result) mustBe FORBIDDEN
    }

    "reject a response with a recipientSignature that doesn't verify" in {
      // bob's stated approval, but signed by alice's key — fails verification before Forbidden/Conflict checks.
      val body = respondBody(alice, pickUpId, approved = true)
      val result = route(app, bob.patch(s"/share-requests/$pickUpId", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "allow the recipient to approve and receive the ciphertext (one-time delivery)" in {
      val body = respondBody(bob, pickUpId, approved = true)
      val result = route(app, bob.patch(s"/share-requests/$pickUpId", body)).get
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "state").as[String] mustBe "approved"
      (json \ "respondedAt").asOpt[String] must not be empty
      (json \ "ciphertext").asOpt[String] must not be empty
      (json \ "recipientSignature").asOpt[String] must not be empty
    }

    "reject a second response to an already-decided PickUp" in {
      val body = respondBody(bob, pickUpId, approved = false)
      val result = route(app, bob.patch(s"/share-requests/$pickUpId", body)).get
      status(result) mustBe CONFLICT
    }

    "return no ciphertext when fetching an already-approved PickUp" in {
      val result = route(app, alice.get(s"/share-requests/$pickUpId")).get
      status(result) mustBe OK
      // ciphertext was cleared from the relay after delivery
      (contentAsJson(result) \ "ciphertext").asOpt[String] mustBe None
    }

    "still block re-deposit after approval (PickUp still active)" in {
      val result = route(app, alice.post("/share-requests", pickUpBody(alice, bob))).get
      status(result) mustBe CONFLICT
    }
  }

  // ── Retrieve flow ──────────────────────────────────────────────────────────

  "POST /share-requests (Retrieve)" should {

    "open a Retrieve request and return a pending ShareRequest" in {
      val result = route(app, alice.post("/share-requests", retrieveBody(pickUpId, bob))).get
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      retrieveId = (json \ "id").as[String]
      retrieveId must not be empty
      (json \ "requestType").as[String] mustBe "retrieve"
      (json \ "state").as[String] mustBe "pending"
      (json \ "shareId").as[String] mustBe pickUpId
      (json \ "requestedAt").asOpt[String] must not be empty
      (json \ "respondedAt").asOpt[String] mustBe None
      (json \ "ciphertext").asOpt[String] mustBe None
    }

    "reject a duplicate pending Retrieve for the same (secretId, senderKey, recipientKey)" in {
      val result = route(app, alice.post("/share-requests", retrieveBody(pickUpId, bob))).get
      status(result) mustBe CONFLICT
    }
  }

  "PATCH /share-requests/:requestId (respond to Retrieve)" should {

    "reject approval of a Retrieve without ciphertext" in {
      val body = respondBody(bob, retrieveId, approved = true)
      val result = route(app, bob.patch(s"/share-requests/$retrieveId", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "allow the recipient to approve a Retrieve by supplying ciphertext" in {
      val body = respondBody(bob, retrieveId, approved = true, ciphertext = Some("AQID"))
      val result = route(app, bob.patch(s"/share-requests/$retrieveId", body)).get
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "state").as[String] mustBe "approved"
      (json \ "respondedAt").asOpt[String] must not be empty
      (json \ "ciphertext").as[String] mustBe "AQID"
    }

    "reject a second response to an already-approved Retrieve" in {
      val body = respondBody(bob, retrieveId, approved = false)
      val result = route(app, bob.patch(s"/share-requests/$retrieveId", body)).get
      status(result) mustBe CONFLICT
    }
  }

  // ── Delete flow ────────────────────────────────────────────────────────────

  "POST /share-requests (Delete) + PATCH (approve Delete)" should {

    "open a Delete request and, when approved, cascade-delete PickUp + Retrieve rows" in {
      // Open a fresh PickUp in a fresh secretId so this doesn't interfere with prior state
      val freshSecretId = UUID.randomUUID().toString
      val freshPickUpSig =
        alice.signOpen(freshSecretId, "pick_up", bob.publicKeyHeader, "cascade test", createdAt, None, Some("AQID"), Some(2), Some(3))
      val freshPickUpBody =
        s"""{"requestType":"pick_up","secretId":"$freshSecretId","label":"cascade test","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","k":2,"n":3,"senderSignature":"$freshPickUpSig"}"""
          .getBytes("UTF-8")
      val pickUp2result = route(app, alice.post("/share-requests", freshPickUpBody)).get
      status(pickUp2result) mustBe CREATED
      val pickUp2Id = (contentAsJson(pickUp2result) \ "id").as[String]

      // Open a Delete request
      val deleteSig =
        alice.signOpen(freshSecretId, "delete", bob.publicKeyHeader, "cascade test", createdAt, Some(pickUp2Id), None)
      val deleteBody =
        s"""{"requestType":"delete","secretId":"$freshSecretId","label":"cascade test","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","shareId":"$pickUp2Id","senderSignature":"$deleteSig"}"""
          .getBytes("UTF-8")
      val deleteResult = route(app, alice.post("/share-requests", deleteBody)).get
      status(deleteResult) mustBe CREATED
      val deleteReqId = (contentAsJson(deleteResult) \ "id").as[String]

      // Bob approves the Delete
      val approveBody = respondBody(bob, deleteReqId, approved = true)
      val approveResult = route(app, bob.patch(s"/share-requests/$deleteReqId", approveBody)).get
      status(approveResult) mustBe OK
      (contentAsJson(approveResult) \ "state").as[String] mustBe "approved"

      // PickUp row should be gone (cascade)
      val pickUpGet = route(app, alice.get(s"/share-requests/$pickUp2Id")).get
      status(pickUpGet) mustBe NOT_FOUND
    }
  }

  // ── Single DELETE ──────────────────────────────────────────────────────────

  "DELETE /share-requests/:requestId" should {

    "allow the sender to delete a PickUp row (cascading Retrieve/Delete)" in {
      // The pickUpId from the main flow is still in the DB
      val result = route(app, alice.delete(s"/share-requests/$pickUpId")).get
      status(result) mustBe NO_CONTENT
    }

    "return 404 for an unknown request ID" in {
      val result = route(app, bob.delete(s"/share-requests/${UUID.randomUUID()}")).get
      status(result) mustBe NOT_FOUND
    }
  }

  // ── Bulk DELETE ────────────────────────────────────────────────────────────

  "DELETE /share-requests (bulk)" should {

    "remove all requests the caller holds as recipient" in {
      // Deposit a fresh share so Bob has at least one row
      val body = pickUpBody(alice, bob, sid = UUID.randomUUID().toString)
      val depositResult = route(app, alice.post("/share-requests", body)).get
      status(depositResult) mustBe CREATED

      val deleteResult = route(app, bob.delete("/share-requests")).get
      status(deleteResult) mustBe NO_CONTENT

      val listResult = route(app, bob.get("/share-requests?role=recipient")).get
      status(listResult) mustBe OK
      contentAsJson(listResult).as[JsArray].value mustBe empty
    }
  }

  // ── RecoveryMetadata push (item 8) ──────────────────────────────────────────

  "POST /share-requests (RecoveryMetadata)" should {

    "self-approve on open — no consent phase, visible to the recipient immediately" in {
      val sid = UUID.randomUUID().toString
      // Bob (the holder) pushes to Alice (the recovering owner) — sender/recipient roles are
      // reversed from a PickUp: the holder is senderKey here.
      val sig = bob.signOpen(sid, "recovery_metadata", alice.publicKeyHeader, "recovered secret", createdAt, None, None, Some(2), Some(3))
      val body =
        s"""{"requestType":"recovery_metadata","secretId":"$sid","label":"recovered secret","recipientKey":"${alice.publicKeyHeader}","secretCreatedAt":"$createdAt","k":2,"n":3,"senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, bob.post("/share-requests", body)).get
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      val id = (json \ "id").as[String]
      (json \ "requestType").as[String] mustBe "recovery_metadata"
      (json \ "state").as[String] mustBe "approved"
      (json \ "respondedAt").asOpt[String] must not be empty
      (json \ "recipientSignature").asOpt[String] mustBe None
      (json \ "shareId").asOpt[String] mustBe None
      (json \ "k").as[Int] mustBe 2
      (json \ "n").as[Int] mustBe 3

      // Alice can already see it as approved — no PATCH needed.
      val listResult = route(app, alice.get("/share-requests?role=recipient&type=recovery_metadata&state=approved")).get
      status(listResult) mustBe OK
      contentAsJson(listResult).as[JsArray].value.exists(j => (j \ "id").as[String] == id) mustBe true

      // Alice deletes it once consumed.
      val deleteResult = route(app, alice.delete(s"/share-requests/$id")).get
      status(deleteResult) mustBe NO_CONTENT
    }

    "reject a RecoveryMetadata push with ciphertext" in {
      val sid = UUID.randomUUID().toString
      val sig = bob.signOpen(sid, "recovery_metadata", alice.publicKeyHeader, "x", createdAt, None, Some("AQID"), Some(2), Some(3))
      val body =
        s"""{"requestType":"recovery_metadata","secretId":"$sid","label":"x","recipientKey":"${alice.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","k":2,"n":3,"senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, bob.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "never conflicts, even pushed repeatedly for the same secretId" in {
      val sid = UUID.randomUUID().toString
      def push() =
        val sig = bob.signOpen(sid, "recovery_metadata", alice.publicKeyHeader, "x", createdAt, None, None, Some(2), Some(3))
        val body =
          s"""{"requestType":"recovery_metadata","secretId":"$sid","label":"x","recipientKey":"${alice.publicKeyHeader}","secretCreatedAt":"$createdAt","k":2,"n":3,"senderSignature":"$sig"}"""
            .getBytes("UTF-8")
        route(app, bob.post("/share-requests", body)).get
      status(push()) mustBe CREATED
      status(push()) mustBe CREATED
    }
  }
