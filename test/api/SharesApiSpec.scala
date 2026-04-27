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

/** Integration tests for POST /shares, GET /shares, DELETE /shares/:shareId.
  *
  * Tests run in declaration order and share a single in-memory H2 database via
  * GuiceOneAppPerSuite. Each suite uses fresh keypairs so data is naturally isolated
  * from other suites even when the DB is shared across the test JVM.
  */
class SharesApiSpec extends PlaySpec with GuiceOneAppPerSuite:

  private val alice    = new RequestSigner()
  private val bob      = new RequestSigner()
  private val secretId = UUID.randomUUID().toString

  // Set by the first test; used by later tests.
  private var shareId: String = ""

  private def depositBody(recipient: RequestSigner, ct: String = "AQID"): Array[Byte] =
    s"""{"secretId":"$secretId","label":"test secret","recipientKey":"${recipient.publicKeyHeader}","ciphertext":"$ct"}"""
      .getBytes("UTF-8")

  "POST /shares" should {

    "deposit a share and return ShareMetadata with all required fields" in {
      val result = route(app, alice.post("/shares", depositBody(bob))).get
      status(result)      mustBe CREATED
      contentType(result) mustBe Some("application/json")
      val json = contentAsJson(result)
      shareId = (json \ "id").as[String]
      shareId                                must not be empty
      (json \ "secretId").as[String]         mustBe secretId
      (json \ "label").as[String]            mustBe "test secret"
      (json \ "senderKey").as[String]        mustBe alice.publicKeyHeader
      (json \ "recipientKey").as[String]     mustBe bob.publicKeyHeader
      (json \ "createdAt").asOpt[String]     must not be empty
    }

    "reject a duplicate deposit for the same (secretId, recipientKey) pair" in {
      val result = route(app, alice.post("/shares", depositBody(bob))).get
      status(result) mustBe CONFLICT
    }
  }

  "GET /shares" should {

    "list the deposited share when queried as sender" in {
      val result = route(app, alice.get("/shares?role=sender")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr.exists(j => (j \ "id").as[String] == shareId) mustBe true
    }

    "list the deposited share when queried as recipient" in {
      val result = route(app, bob.get("/shares?role=recipient")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr.exists(j => (j \ "id").as[String] == shareId) mustBe true
    }

    "return an empty list for a key that holds no shares" in {
      val stranger = new RequestSigner()
      val result   = route(app, stranger.get("/shares?role=recipient")).get
      status(result) mustBe OK
      contentAsJson(result).as[JsArray].value mustBe empty
    }
  }

  "GET /shares/:shareId" should {

    "allow the recipient to pick up their share (one-time delivery)" in {
      val result = route(app, bob.get(s"/shares/$shareId")).get
      status(result)      mustBe OK
      contentType(result) mustBe Some("application/json")
      (contentAsJson(result) \ "ciphertext").asOpt[String] must not be empty
    }

    "return 409 when the share has already been picked up" in {
      val result = route(app, bob.get(s"/shares/$shareId")).get
      status(result) mustBe CONFLICT
    }

    "return 403 when the caller is not the recipient" in {
      val result = route(app, alice.get(s"/shares/$shareId")).get
      status(result) mustBe FORBIDDEN
    }
  }

  "DELETE /shares/:shareId" should {

    "allow the sender to delete the share row after pickup" in {
      val result = route(app, alice.delete(s"/shares/$shareId")).get
      status(result) mustBe NO_CONTENT
    }

    "return 404 for an unknown share ID" in {
      val result = route(app, bob.delete(s"/shares/${UUID.randomUUID()}")).get
      status(result) mustBe NOT_FOUND
    }
  }
