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

import driving_ports.Shares
import play.api.libs.json.*
import play.api.mvc.*
import value_objects.*
import java.time.Instant
import java.util.{Base64, UUID}
import javax.inject.*

@Singleton
class SharesController @Inject() (
    val controllerComponents: ControllerComponents,
    shares: Shares
) extends BaseController
    with ApiSupport:

  private val b64Dec = Base64.getDecoder

  /** POST /shares — deposit a share (Message 1). */
  def depositShare() = Action(parse.raw) { (request: Request[RawBuffer]) =>
    val bodyBytes = request.body.asBytes().map(_.toArray).getOrElse(Array.empty[Byte])
    val result = for
      callerKey    <- AuthHelper.verify(request, bodyBytes)
      json         <- parseJson(bodyBytes)
      secretIdStr  <- (json \ "secretId").asOpt[String]
                        .toRight(BadRequest(errorJson("missing_field", "secretId is required")))
      secretId     <- parseUuid(secretIdStr).map(SecretId(_))
                        .toRight(BadRequest(errorJson("invalid_field", "secretId must be a UUID")))
      labelStr     <- (json \ "label").asOpt[String].filter(_.nonEmpty)
                        .toRight(BadRequest(errorJson("missing_field", "label is required and must be non-empty")))
      rkStr        <- (json \ "recipientKey").asOpt[String]
                        .toRight(BadRequest(errorJson("missing_field", "recipientKey is required")))
      recipientKey <- PublicKey.fromBase64Url(rkStr)
                        .left.map(e => BadRequest(errorJson("invalid_field", e)))
      ctStr        <- (json \ "ciphertext").asOpt[String]
                        .toRight(BadRequest(errorJson("missing_field", "ciphertext is required")))
      ciphertext   <- decodeBase64(ctStr)
                        .toRight(BadRequest(errorJson("invalid_field", "ciphertext must be valid base64")))
      meta         <- shares.depositShare(callerKey, recipientKey, secretId, Label(labelStr), Instant.now(), ciphertext)
                        .left.map(domainErrorToResult)
    yield Created(shareMetadataJson(meta))
    result.merge
  }

  /** GET /shares?role=sender|recipient[&counterpartyKey=...] — list share metadata (Message 2). */
  def listShares() = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey       <- AuthHelper.verify(request, Array.empty)
      roleStr         <- request.getQueryString("role")
                           .toRight(BadRequest(errorJson("missing_param", "role is required")))
      asSender        <- roleStr match
        case "sender"    => Right(true)
        case "recipient" => Right(false)
        case _           => Left(BadRequest(errorJson("invalid_param", "role must be 'sender' or 'recipient'")))
      counterpartyKey  = request.getQueryString("counterpartyKey")
                           .flatMap(s => PublicKey.fromBase64Url(s).toOption)
      metas           <- shares.listShares(callerKey, asSender, counterpartyKey)
                           .left.map(domainErrorToResult)
    yield Ok(JsArray(metas.map(shareMetadataJson).toSeq))
    result.merge
  }

  /** DELETE /shares/:shareId — recipient-initiated deletion without consent. */
  def deleteShare(shareId: String) = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      id        <- parseUuid(shareId)
                     .toRight(BadRequest(errorJson("invalid_param", "shareId must be a UUID")))
      _         <- shares.deleteShareById(callerKey, id)
                     .left.map(domainErrorToResult)
    yield NoContent
    result.merge
  }

  private def parseJson(bytes: Array[Byte]): Either[Result, JsValue] =
    try Right(Json.parse(bytes))
    catch case e: Exception => Left(BadRequest(errorJson("invalid_json", e.getMessage)))

  private def parseUuid(s: String): Option[UUID] =
    try Some(UUID.fromString(s)) catch case _: Exception => None

  private def decodeBase64(s: String): Option[Array[Byte]] =
    try Some(b64Dec.decode(s)) catch case _: Exception => None
