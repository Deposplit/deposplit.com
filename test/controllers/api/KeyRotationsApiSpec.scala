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

/** Integration tests for POST/GET/DELETE /key-rotations (item 9's signed `rotate(K_old ->
  * K_new)` push). Runs in declaration order and shares a single in-memory H2 database via
  * GuiceOneAppPerSuite, same pattern as `ShareRequestsApiSpec`.
  *
  * `newX25519Key`'s wire value only needs to be a 32-byte base64url string — the relay never
  * performs key agreement with it, so a second `RequestSigner`'s Ed25519 public key doubles as a
  * conveniently-shaped stand-in without needing real X25519 key generation in the test.
  */
class KeyRotationsApiSpec extends PlaySpec with GuiceOneAppPerSuite:

  private val alice = new RequestSigner()
  private val bob = new RequestSigner()
  private val charlie = new RequestSigner()
  private val newX25519Key = charlie.publicKeyHeader

  private def pushBody(
      oldKey: RequestSigner,
      recipient: RequestSigner,
      newEd25519Key: RequestSigner = charlie,
      newX25519: String = newX25519Key
  ): Array[Byte] =
    val sig = oldKey.signRotation(recipient.publicKeyHeader, newEd25519Key.publicKeyHeader, newX25519)
    s"""{
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "newEd25519Key":   "${newEd25519Key.publicKeyHeader}",
       |  "newX25519Key":    "$newX25519",
       |  "signature":       "$sig"
       |}""".stripMargin.getBytes("UTF-8")

  "POST /key-rotations" should {

    "push a rotation notice and return it with all fields" in {
      val result = route(app, alice.post("/key-rotations", pushBody(alice, bob))).get
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      (json \ "id").as[String] must not be empty
      (json \ "oldEd25519Key").as[String] mustBe alice.publicKeyHeader
      (json \ "recipientKey").as[String] mustBe bob.publicKeyHeader
      (json \ "newEd25519Key").as[String] mustBe charlie.publicKeyHeader
      (json \ "newX25519Key").as[String] mustBe newX25519Key
      (json \ "signature").as[String] must not be empty
      (json \ "createdAt").asOpt[String] must not be empty
    }

    "reject a rotation whose signature doesn't verify against the caller" in {
      // Signed as if by bob, but posted (and transport-authenticated) as alice.
      val sig = bob.signRotation(bob.publicKeyHeader, charlie.publicKeyHeader, newX25519Key)
      val body =
        s"""{"recipientKey":"${bob.publicKeyHeader}","newEd25519Key":"${charlie.publicKeyHeader}","newX25519Key":"$newX25519Key","signature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/key-rotations", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a rotation with a missing field" in {
      val body = s"""{"recipientKey":"${bob.publicKeyHeader}"}""".getBytes("UTF-8")
      val result = route(app, alice.post("/key-rotations", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "never conflicts, even pushed repeatedly to the same recipient" in {
      status(route(app, alice.post("/key-rotations", pushBody(alice, bob))).get) mustBe CREATED
      status(route(app, alice.post("/key-rotations", pushBody(alice, bob))).get) mustBe CREATED
    }
  }

  "GET /key-rotations" should {

    "list notices addressed to the caller" in {
      val result = route(app, bob.get("/key-rotations")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr must not be empty
      arr.forall(j => (j \ "recipientKey").as[String] == bob.publicKeyHeader) mustBe true
    }

    "return an empty list for a key with no notices" in {
      val stranger = new RequestSigner()
      val result = route(app, stranger.get("/key-rotations")).get
      status(result) mustBe OK
      contentAsJson(result).as[JsArray].value mustBe empty
    }
  }

  "DELETE /key-rotations/:id" should {

    "allow the recipient to delete a notice once consumed" in {
      val pushed = route(app, alice.post("/key-rotations", pushBody(alice, bob))).get
      status(pushed) mustBe CREATED
      val id = (contentAsJson(pushed) \ "id").as[String]

      val deleteResult = route(app, bob.delete(s"/key-rotations/$id")).get
      status(deleteResult) mustBe NO_CONTENT

      val listResult = route(app, bob.get("/key-rotations")).get
      contentAsJson(listResult).as[JsArray].value.exists(j => (j \ "id").as[String] == id) mustBe false
    }

    "return 403 when the caller is not the recipient" in {
      val pushed = route(app, alice.post("/key-rotations", pushBody(alice, bob))).get
      val id = (contentAsJson(pushed) \ "id").as[String]

      val deleteResult = route(app, charlie.delete(s"/key-rotations/$id")).get
      status(deleteResult) mustBe FORBIDDEN
    }

    "return 404 for an unknown id" in {
      val result = route(app, bob.delete(s"/key-rotations/${UUID.randomUUID()}")).get
      status(result) mustBe NOT_FOUND
    }
  }
