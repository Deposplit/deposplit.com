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
  * `newEncKey`'s wire value only needs to be a 32-byte base64url string — the relay never
  * performs key agreement with it, so a second `RequestSigner`'s Ed25519 public key doubles as a
  * conveniently-shaped stand-in without needing real X25519 key generation in the test.
  */
class KeyRotationsApiSpec extends PlaySpec with GuiceOneAppPerSuite:

  private val alice = new RequestSigner()
  private val bob = new RequestSigner()
  private val charlie = new RequestSigner()
  private val newEncKey = charlie.publicKeyHeader
  private val cipherSuite = "ed25519+x25519-v1"

  private def pushBody(
      oldKey: RequestSigner,
      recipient: RequestSigner,
      newVerifyKey: RequestSigner = charlie,
      newEnc: String = newEncKey
  ): Array[Byte] =
    val sig = oldKey.signRotation(recipient.publicKeyHeader, newVerifyKey.publicKeyHeader, newEnc, cipherSuite)
    s"""{
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "newVerifyKey":    "${newVerifyKey.publicKeyHeader}",
       |  "newEncKey":       "$newEnc",
       |  "newCipherSuite":  "$cipherSuite",
       |  "signature":       "$sig"
       |}""".stripMargin.getBytes("UTF-8")

  "POST /key-rotations" should {

    "push a rotation notice and return it with all fields" in {
      val result = route(app, alice.post("/key-rotations", pushBody(alice, bob))).get
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      (json \ "id").as[String] must not be empty
      (json \ "oldVerifyKey").as[String] mustBe alice.publicKeyHeader
      (json \ "recipientKey").as[String] mustBe bob.publicKeyHeader
      (json \ "newVerifyKey").as[String] mustBe charlie.publicKeyHeader
      (json \ "newEncKey").as[String] mustBe newEncKey
      (json \ "newCipherSuite").as[String] mustBe cipherSuite
      (json \ "signature").as[String] must not be empty
      (json \ "createdAt").asOpt[String] must not be empty
    }

    "reject a rotation whose signature doesn't verify against the caller" in {
      // Signed as if by bob, but posted (and transport-authenticated) as alice.
      val sig = bob.signRotation(bob.publicKeyHeader, charlie.publicKeyHeader, newEncKey, cipherSuite)
      val body =
        s"""{"recipientKey":"${bob.publicKeyHeader}","newVerifyKey":"${charlie.publicKeyHeader}","newEncKey":"$newEncKey","newCipherSuite":"$cipherSuite","signature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/key-rotations", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a rotation with an unknown cipher suite" in {
      val sig = alice.signRotation(bob.publicKeyHeader, charlie.publicKeyHeader, newEncKey, "made-up-suite")
      val body =
        s"""{"recipientKey":"${bob.publicKeyHeader}","newVerifyKey":"${charlie.publicKeyHeader}","newEncKey":"$newEncKey","newCipherSuite":"made-up-suite","signature":"$sig"}"""
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
