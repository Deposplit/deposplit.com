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

/** Integration tests for POST/GET /custody-heartbeats (item 12's signed custodial-heartbeat push). Runs in declaration
  * order and shares a single in-memory H2 database via GuiceOneAppPerSuite, same pattern as `KeyRotationsApiSpec`.
  */
class CustodyHeartbeatsApiSpec extends PlaySpec with GuiceOneAppPerSuite:

  private val alice = new RequestSigner()
  private val bob = new RequestSigner()
  private val charlie = new RequestSigner()

  private def pushBody(
      holder: RequestSigner,
      owner: RequestSigner,
      secretIds: Seq[String] = Seq(UUID.randomUUID().toString),
      optedOut: Boolean = false
  ): Array[Byte] =
    val sig = holder.signHeartbeat(owner.publicKeyHeader, secretIds, optedOut)
    val secretIdsJson = secretIds.map(id => s"\"$id\"").mkString("[", ",", "]")
    s"""{
       |  "ownerKey":  "${owner.publicKeyHeader}",
       |  "secretIds": $secretIdsJson,
       |  "optedOut":  $optedOut,
       |  "signature": "$sig"
       |}""".stripMargin.getBytes("UTF-8")

  "POST /custody-heartbeats" should {

    "push a heartbeat and return it with all fields" in {
      val secretId = UUID.randomUUID().toString
      val result = route(app, bob.post("/custody-heartbeats", pushBody(bob, alice, Seq(secretId)))).get
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      (json \ "id").as[String] must not be empty
      (json \ "holderKey").as[String] mustBe bob.publicKeyHeader
      (json \ "ownerKey").as[String] mustBe alice.publicKeyHeader
      (json \ "secretIds").as[Seq[String]] mustBe Seq(secretId)
      (json \ "optedOut").as[Boolean] mustBe false
      (json \ "signature").as[String] must not be empty
      (json \ "createdAt").asOpt[String] must not be empty
    }

    "reject a heartbeat whose signature doesn't verify against the caller" in {
      // Signed as if by alice, but posted (and transport-authenticated) as bob.
      val secretId = UUID.randomUUID().toString
      val sig = alice.signHeartbeat(alice.publicKeyHeader, Seq(secretId), false)
      val body =
        s"""{"ownerKey":"${alice.publicKeyHeader}","secretIds":["$secretId"],"optedOut":false,"signature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, bob.post("/custody-heartbeats", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a heartbeat with a missing field" in {
      val body = s"""{"ownerKey":"${alice.publicKeyHeader}"}""".getBytes("UTF-8")
      val result = route(app, bob.post("/custody-heartbeats", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "upsert on repeated pushes to the same owner — never conflicts, only the latest is kept" in {
      status(route(app, bob.post("/custody-heartbeats", pushBody(bob, alice))).get) mustBe CREATED
      status(route(app, bob.post("/custody-heartbeats", pushBody(bob, alice))).get) mustBe CREATED

      val listResult = route(app, alice.get("/custody-heartbeats")).get
      contentAsJson(listResult)
        .as[JsArray]
        .value
        .count(j => (j \ "holderKey").as[String] == bob.publicKeyHeader) mustBe 1
    }

    "an opted-out push replaces a prior non-opted-out heartbeat from the same holder" in {
      status(route(app, charlie.post("/custody-heartbeats", pushBody(charlie, alice))).get) mustBe CREATED
      status(
        route(app, charlie.post("/custody-heartbeats", pushBody(charlie, alice, Seq.empty, optedOut = true))).get
      ) mustBe CREATED

      val listResult = route(app, alice.get("/custody-heartbeats")).get
      val fromCharlie =
        contentAsJson(listResult).as[JsArray].value.filter(j => (j \ "holderKey").as[String] == charlie.publicKeyHeader)
      fromCharlie.size mustBe 1
      (fromCharlie.head \ "optedOut").as[Boolean] mustBe true
    }
  }

  "GET /custody-heartbeats" should {

    "list heartbeats addressed to the caller" in {
      val result = route(app, alice.get("/custody-heartbeats")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr must not be empty
      arr.forall(j => (j \ "ownerKey").as[String] == alice.publicKeyHeader) mustBe true
    }

    "return an empty list for a key with no heartbeats" in {
      val stranger = new RequestSigner()
      val result = route(app, stranger.get("/custody-heartbeats")).get
      status(result) mustBe OK
      contentAsJson(result).as[JsArray].value mustBe empty
    }
  }
