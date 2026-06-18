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

package api

import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import play.api.libs.json.*
import play.api.test.*
import play.api.test.Helpers.*

import java.util.UUID

/** Integration tests for POST/GET/PATCH/DELETE /share-requests.
  *
  * Tests run in declaration order and share a single in-memory H2 database via
  * GuiceOneAppPerSuite. Each suite uses fresh keypairs so data is naturally isolated
  * from other suites even when the DB is shared across the test JVM.
  */
class ShareRequestsApiSpec extends PlaySpec with GuiceOneAppPerSuite:

  private val alice    = new RequestSigner()
  private val bob      = new RequestSigner()
  private val secretId = UUID.randomUUID().toString

  // Populated by tests; used by subsequent tests in declaration order.
  private var pickUpId:   String = ""
  private var retrieveId: String = ""

  // ── Body builders ──────────────────────────────────────────────────────────

  private def pickUpBody(
      sender: RequestSigner,
      recipient: RequestSigner,
      sid: String = secretId,
      ct: String = "AQID"
  ): Array[Byte] =
    s"""{
       |  "requestType":     "pick_up",
       |  "secretId":        "$sid",
       |  "label":           "test secret",
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "secretCreatedAt": "2026-01-01T00:00:00Z",
       |  "ciphertext":      "$ct"
       |}""".stripMargin.getBytes("UTF-8")

  private def retrieveBody(
      shareId: String,
      recipient: RequestSigner
  ): Array[Byte] =
    s"""{
       |  "requestType":     "retrieve",
       |  "secretId":        "$secretId",
       |  "label":           "test secret",
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "secretCreatedAt": "2026-01-01T00:00:00Z",
       |  "shareId":         "$shareId"
       |}""".stripMargin.getBytes("UTF-8")

  private def deleteRequestBody(
      shareId: String,
      recipient: RequestSigner
  ): Array[Byte] =
    s"""{
       |  "requestType":     "delete",
       |  "secretId":        "$secretId",
       |  "label":           "test secret",
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "secretCreatedAt": "2026-01-01T00:00:00Z",
       |  "shareId":         "$shareId"
       |}""".stripMargin.getBytes("UTF-8")

  // ── PickUp (deposit) flow ──────────────────────────────────────────────────

  "POST /share-requests (PickUp)" should {

    "deposit a share and return a PickUp ShareRequest with all required fields" in {
      val result = route(app, alice.post("/share-requests", pickUpBody(alice, bob))).get
      status(result)      mustBe CREATED
      contentType(result) mustBe Some("application/json")
      val json = contentAsJson(result)
      pickUpId = (json \ "id").as[String]
      pickUpId                                  must not be empty
      (json \ "requestType").as[String]         mustBe "pick_up"
      (json \ "state").as[String]               mustBe "pending"
      (json \ "secretId").as[String]            mustBe secretId
      (json \ "label").as[String]               mustBe "test secret"
      (json \ "senderKey").as[String]           mustBe alice.publicKeyHeader
      (json \ "recipientKey").as[String]        mustBe bob.publicKeyHeader
      (json \ "secretCreatedAt").asOpt[String]  must not be empty
      (json \ "requestedAt").asOpt[String]      must not be empty
      (json \ "respondedAt").asOpt[String]      mustBe None
      (json \ "shareId").asOpt[String]          mustBe None
      // ciphertext not returned on PickUp creation (only on approval)
      (json \ "ciphertext").asOpt[String]       mustBe None
    }

    "reject a duplicate active PickUp for the same (secretId, recipientKey)" in {
      val result = route(app, alice.post("/share-requests", pickUpBody(alice, bob))).get
      status(result) mustBe CONFLICT
    }

    "reject a PickUp without ciphertext" in {
      val body = s"""{"requestType":"pick_up","secretId":"${UUID.randomUUID()}","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"2026-01-01T00:00:00Z"}"""
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
      val result   = route(app, stranger.get("/share-requests?role=recipient")).get
      status(result) mustBe OK
      contentAsJson(result).as[JsArray].value mustBe empty
    }
  }

  "GET /share-requests/:requestId" should {

    "return the PickUp to the sender" in {
      val result = route(app, alice.get(s"/share-requests/$pickUpId")).get
      status(result)      mustBe OK
      val json = contentAsJson(result)
      (json \ "id").as[String]    mustBe pickUpId
      (json \ "state").as[String] mustBe "pending"
    }

    "return the PickUp to the recipient" in {
      val result = route(app, bob.get(s"/share-requests/$pickUpId")).get
      status(result) mustBe OK
      (contentAsJson(result) \ "id").as[String] mustBe pickUpId
    }

    "reject access by an unrelated third party" in {
      val stranger = new RequestSigner()
      val result   = route(app, stranger.get(s"/share-requests/$pickUpId")).get
      status(result) mustBe FORBIDDEN
    }
  }

  // ── Approve PickUp (Bob collects the share) ────────────────────────────────

  "PATCH /share-requests/:requestId (approve PickUp)" should {

    "reject a response from the sender (who is not the recipient)" in {
      val body   = """{"state":"approved"}""".getBytes("UTF-8")
      val result = route(app, alice.patch(s"/share-requests/$pickUpId", body)).get
      status(result) mustBe FORBIDDEN
    }

    "allow the recipient to approve and receive the ciphertext (one-time delivery)" in {
      val body   = """{"state":"approved"}""".getBytes("UTF-8")
      val result = route(app, bob.patch(s"/share-requests/$pickUpId", body)).get
      status(result)      mustBe OK
      val json = contentAsJson(result)
      (json \ "state").as[String]            mustBe "approved"
      (json \ "respondedAt").asOpt[String]   must not be empty
      (json \ "ciphertext").asOpt[String]    must not be empty
    }

    "reject a second response to an already-decided PickUp" in {
      val body   = """{"state":"denied"}""".getBytes("UTF-8")
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
      retrieveId                               must not be empty
      (json \ "requestType").as[String]        mustBe "retrieve"
      (json \ "state").as[String]              mustBe "pending"
      (json \ "shareId").as[String]            mustBe pickUpId
      (json \ "requestedAt").asOpt[String]     must not be empty
      (json \ "respondedAt").asOpt[String]     mustBe None
      (json \ "ciphertext").asOpt[String]      mustBe None
    }

    "reject a duplicate pending Retrieve for the same (secretId, senderKey, recipientKey)" in {
      val result = route(app, alice.post("/share-requests", retrieveBody(pickUpId, bob))).get
      status(result) mustBe CONFLICT
    }
  }

  "PATCH /share-requests/:requestId (respond to Retrieve)" should {

    "reject approval of a Retrieve without ciphertext" in {
      val body   = """{"state":"approved"}""".getBytes("UTF-8")
      val result = route(app, bob.patch(s"/share-requests/$retrieveId", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "allow the recipient to approve a Retrieve by supplying ciphertext" in {
      val body   = """{"state":"approved","ciphertext":"AQID"}""".getBytes("UTF-8")
      val result = route(app, bob.patch(s"/share-requests/$retrieveId", body)).get
      status(result)      mustBe OK
      val json = contentAsJson(result)
      (json \ "state").as[String]          mustBe "approved"
      (json \ "respondedAt").asOpt[String] must not be empty
      (json \ "ciphertext").as[String]     mustBe "AQID"
    }

    "reject a second response to an already-approved Retrieve" in {
      val body   = """{"state":"denied"}""".getBytes("UTF-8")
      val result = route(app, bob.patch(s"/share-requests/$retrieveId", body)).get
      status(result) mustBe CONFLICT
    }
  }

  // ── Delete flow ────────────────────────────────────────────────────────────

  "POST /share-requests (Delete) + PATCH (approve Delete)" should {

    "open a Delete request and, when approved, cascade-delete PickUp + Retrieve rows" in {
      // Open a fresh PickUp in a fresh secretId so this doesn't interfere with prior state
      val freshSecretId = UUID.randomUUID().toString
      val freshPickUpBody =
        s"""{"requestType":"pick_up","secretId":"$freshSecretId","label":"cascade test","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"2026-01-01T00:00:00Z","ciphertext":"AQID"}"""
          .getBytes("UTF-8")
      val pickUp2result = route(app, alice.post("/share-requests", freshPickUpBody)).get
      status(pickUp2result) mustBe CREATED
      val pickUp2Id = (contentAsJson(pickUp2result) \ "id").as[String]

      // Open a Delete request
      val deleteBody =
        s"""{"requestType":"delete","secretId":"$freshSecretId","label":"cascade test","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"2026-01-01T00:00:00Z","shareId":"$pickUp2Id"}"""
          .getBytes("UTF-8")
      val deleteResult = route(app, alice.post("/share-requests", deleteBody)).get
      status(deleteResult) mustBe CREATED
      val deleteReqId = (contentAsJson(deleteResult) \ "id").as[String]

      // Bob approves the Delete
      val approveBody = """{"state":"approved"}""".getBytes("UTF-8")
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
