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

/** Integration tests for the /share-requests endpoints.
  *
  * Tests run in declaration order: earlier tests deposit a share and open a request;
  * later tests approve that request and verify idempotency / access control.
  */
class ShareRequestsApiSpec extends PlaySpec with GuiceOneAppPerSuite:

  private val alice = new RequestSigner()
  private val bob   = new RequestSigner()

  // Populated by setup helpers called from the first tests.
  private var shareId:   String = ""
  private var requestId: String = ""

  private def depositShare(sender: RequestSigner, recipient: RequestSigner, ct: String = "AQID"): String =
    val body = s"""{"secretId":"${UUID.randomUUID()}","label":"test","recipientKey":"${recipient.publicKeyHeader}","ciphertext":"$ct"}"""
      .getBytes("UTF-8")
    val result = route(app, sender.post("/shares", body)).get
    status(result) mustBe CREATED
    (contentAsJson(result) \ "id").as[String]

  private def openRequest(sender: RequestSigner, sid: String, reqType: String): String =
    val body   = s"""{"shareId":"$sid","requestType":"$reqType"}""".getBytes("UTF-8")
    val result = route(app, sender.post("/share-requests", body)).get
    status(result) mustBe CREATED
    (contentAsJson(result) \ "id").as[String]

  "POST /share-requests" should {

    "open a retrieve request as the sender and return a pending ShareRequest" in {
      shareId   = depositShare(alice, bob)
      val body  = s"""{"shareId":"$shareId","requestType":"retrieve"}""".getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      requestId = (json \ "id").as[String]
      requestId                                must not be empty
      (json \ "state").as[String]              mustBe "pending"
      (json \ "requestType").as[String]        mustBe "retrieve"
      (json \ "share" \ "id").as[String]       mustBe shareId
      (json \ "requestedAt").asOpt[String]     must not be empty
      (json \ "respondedAt").asOpt[String]     mustBe None
      (json \ "ciphertext").asOpt[String]      mustBe None
    }

    "reject a duplicate pending request for the same (share, type)" in {
      val body   = s"""{"shareId":"$shareId","requestType":"retrieve"}""".getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe CONFLICT
    }

    "reject a request opened by a non-sender" in {
      val otherShareId = depositShare(alice, bob)
      val body         = s"""{"shareId":"$otherShareId","requestType":"retrieve"}""".getBytes("UTF-8")
      val result       = route(app, bob.post("/share-requests", body)).get
      status(result) mustBe FORBIDDEN
    }
  }

  "GET /share-requests" should {

    "list the open request when queried as sender" in {
      val result = route(app, alice.get("/share-requests?role=sender")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr.exists(j => (j \ "id").as[String] == requestId) mustBe true
    }

    "list the open request when queried as recipient with state=pending" in {
      val result = route(app, bob.get("/share-requests?role=recipient&state=pending")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr.exists(j => (j \ "id").as[String] == requestId) mustBe true
    }
  }

  "GET /share-requests/:requestId" should {

    "return the request to the sender" in {
      val result = route(app, alice.get(s"/share-requests/$requestId")).get
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "id").as[String]             mustBe requestId
      (json \ "state").as[String]          mustBe "pending"
      (json \ "requestedAt").asOpt[String] must not be empty
    }

    "return the request to the recipient" in {
      val result = route(app, bob.get(s"/share-requests/$requestId")).get
      status(result) mustBe OK
      (contentAsJson(result) \ "id").as[String] mustBe requestId
    }

    "reject access by an unrelated third party" in {
      val stranger = new RequestSigner()
      val result   = route(app, stranger.get(s"/share-requests/$requestId")).get
      status(result) mustBe FORBIDDEN
    }
  }

  "PATCH /share-requests/:requestId" should {

    "reject a response from the sender (who is not the recipient)" in {
      val body   = """{"state":"approved"}""".getBytes("UTF-8")
      val result = route(app, alice.patch(s"/share-requests/$requestId", body)).get
      status(result) mustBe FORBIDDEN
    }

    "return 400 when the recipient approves a retrieve request without providing ciphertext" in {
      val body   = """{"state":"approved"}""".getBytes("UTF-8")
      val result = route(app, bob.patch(s"/share-requests/$requestId", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "allow the recipient to approve a retrieve request with ciphertext from local storage" in {
      val body   = """{"state":"approved","ciphertext":"AQID"}""".getBytes("UTF-8")
      val result = route(app, bob.patch(s"/share-requests/$requestId", body)).get
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "state").as[String]          mustBe "approved"
      (json \ "respondedAt").asOpt[String] must not be empty
      (json \ "ciphertext").as[String]     mustBe "AQID"
    }

    "reject a second response to an already-decided request" in {
      val body   = """{"state":"denied"}""".getBytes("UTF-8")
      val result = route(app, bob.patch(s"/share-requests/$requestId", body)).get
      status(result) mustBe CONFLICT
    }
  }
