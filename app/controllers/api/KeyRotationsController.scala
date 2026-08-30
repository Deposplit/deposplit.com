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

import driving_ports.KeyRotations
import jakarta.inject.*
import play.api.libs.json.*
import play.api.mvc.*
import value_objects.*

import java.util.UUID

/** The signed `rotate(K_old -> K_new)` push — see `driving_ports.KeyRotations`. */
class KeyRotationsController @Inject() (
    val controllerComponents: ControllerComponents,
    rotations: KeyRotations
) extends BaseController,
      ApiSupport:

  /** POST /key-rotations — push a signed rotation notice to one contact. The authenticated caller becomes
    * `oldVerifyKey`.
    */
  def pushRotation() = Action(parse.raw) { (request: Request[RawBuffer]) =>
    val bodyBytes = request.body.asBytes().map(_.toArray).getOrElse(Array.empty[Byte])
    val result = for
      callerKey <- AuthHelper.verify(request, bodyBytes)
      json <- parseJson(bodyBytes)
      rkStr <- (json \ "recipientKey")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "recipientKey is required")))
      recipientKey <- PublicKey.fromBase64Url(rkStr).left.map(e => BadRequest(errorJson("invalid_field", e)))
      newVerifyStr <- (json \ "newVerifyKey")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "newVerifyKey is required")))
      newVerifyKey <- PublicKey.fromBase64Url(newVerifyStr).left.map(e => BadRequest(errorJson("invalid_field", e)))
      newEncStr <- (json \ "newEncKey")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "newEncKey is required")))
      newEncKey <- X25519Key.fromBase64Url(newEncStr).left.map(e => BadRequest(errorJson("invalid_field", e)))
      suiteStr <- (json \ "newCipherSuite")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "newCipherSuite is required")))
      newCipherSuite <- CipherSuite
        .fromWire(suiteStr)
        .toRight(BadRequest(errorJson("invalid_field", s"unknown cipher suite: $suiteStr")))
      sigStr <- (json \ "signature")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "signature is required")))
      signature <- Signature.fromBase64Url(sigStr).left.map(e => BadRequest(errorJson("invalid_field", e)))
      rotation <- rotations
        .pushRotation(callerKey, recipientKey, newVerifyKey, newEncKey, newCipherSuite, signature)
        .left
        .map(domainErrorToResult)
    yield Created(keyRotationJson(rotation))
    result.merge
  }

  /** GET /key-rotations — rotation notices addressed to the caller. */
  def listRotations() = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      rs <- rotations.listRotations(callerKey).left.map(domainErrorToResult)
    yield Ok(JsArray(rs.map(keyRotationJson).toSeq))
    result.merge
  }

  /** DELETE /key-rotations/:id — the recipient deletes a notice once consumed. */
  def deleteRotation(id: String) = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      uuid <- parseUuid(id).toRight(BadRequest(errorJson("invalid_param", "id must be a UUID")))
      _ <- rotations.deleteRotation(callerKey, uuid).left.map(domainErrorToResult)
    yield NoContent
    result.merge
  }

  private def parseJson(bytes: Array[Byte]): Either[Result, JsValue] =
    try Right(Json.parse(bytes))
    catch case e: Exception => Left(BadRequest(errorJson("invalid_json", e.getMessage)))

  private def parseUuid(s: String): Option[UUID] =
    try Some(UUID.fromString(s))
    catch case _: Exception => None
