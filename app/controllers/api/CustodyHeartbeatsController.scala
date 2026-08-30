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

import driving_ports.CustodyHeartbeats
import jakarta.inject.*
import play.api.libs.json.*
import play.api.mvc.*
import value_objects.*

import java.util.UUID

/** The signed custodial-heartbeat push — see `driving_ports.CustodyHeartbeats`. */
class CustodyHeartbeatsController @Inject() (
    val controllerComponents: ControllerComponents,
    heartbeats: CustodyHeartbeats
) extends BaseController,
      ApiSupport:

  /** POST /custody-heartbeats — push (upsert) a signed heartbeat for one owner. The authenticated caller becomes
    * `holderKey`.
    */
  def pushHeartbeat() = Action(parse.raw) { (request: Request[RawBuffer]) =>
    val bodyBytes = request.body.asBytes().map(_.toArray).getOrElse(Array.empty[Byte])
    val result = for
      callerKey <- AuthHelper.verify(request, bodyBytes)
      json <- parseJson(bodyBytes)
      ownerKeyStr <- (json \ "ownerKey")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "ownerKey is required")))
      ownerKey <- PublicKey.fromBase64Url(ownerKeyStr).left.map(e => BadRequest(errorJson("invalid_field", e)))
      secretIdStrs <- (json \ "secretIds")
        .asOpt[Seq[String]]
        .toRight(BadRequest(errorJson("missing_field", "secretIds is required")))
      secretIds <- parseUuids(secretIdStrs).toRight(BadRequest(errorJson("invalid_field", "secretIds must be UUIDs")))
      optedOut <- (json \ "optedOut")
        .asOpt[Boolean]
        .toRight(BadRequest(errorJson("missing_field", "optedOut is required")))
      sigStr <- (json \ "signature")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "signature is required")))
      signature <- Signature.fromBase64Url(sigStr).left.map(e => BadRequest(errorJson("invalid_field", e)))
      heartbeat <- heartbeats
        .pushHeartbeat(callerKey, ownerKey, secretIds, optedOut, signature)
        .left
        .map(domainErrorToResult)
    yield Created(custodyHeartbeatJson(heartbeat))
    result.merge
  }

  /** GET /custody-heartbeats — the latest heartbeat from each holder addressed to the caller. */
  def listHeartbeats() = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      hs <- heartbeats.listHeartbeats(callerKey).left.map(domainErrorToResult)
    yield Ok(JsArray(hs.map(custodyHeartbeatJson).toSeq))
    result.merge
  }

  private def parseJson(bytes: Array[Byte]): Either[Result, JsValue] =
    try Right(Json.parse(bytes))
    catch case e: Exception => Left(BadRequest(errorJson("invalid_json", e.getMessage)))

  private def parseUuids(strs: Seq[String]): Option[Seq[UUID]] =
    try Some(strs.map(UUID.fromString))
    catch case _: Exception => None
