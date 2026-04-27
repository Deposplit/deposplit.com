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
import java.util.UUID
import javax.inject.*

@Singleton
class ShareRequestsController @Inject() (
    val controllerComponents: ControllerComponents,
    shares: Shares
) extends BaseController
    with ApiSupport:

  /** POST /share-requests — open a consent request (Messages 3 & 4, sender side). */
  def openShareRequest() = Action(parse.raw) { (request: Request[RawBuffer]) =>
    val bodyBytes = request.body.asBytes().map(_.toArray).getOrElse(Array.empty[Byte])
    val result = for
      callerKey   <- AuthHelper.verify(request, bodyBytes)
      json        <- parseJson(bodyBytes)
      shareIdStr  <- (json \ "shareId").asOpt[String]
                       .toRight(BadRequest(errorJson("missing_field", "shareId is required")))
      shareId     <- parseUuid(shareIdStr)
                       .toRight(BadRequest(errorJson("invalid_field", "shareId must be a UUID")))
      rtStr       <- (json \ "requestType").asOpt[String]
                       .toRight(BadRequest(errorJson("missing_field", "requestType is required")))
      requestType <- rtStr match
        case "retrieve" => Right(ShareRequestType.Retrieve)
        case "delete"   => Right(ShareRequestType.Delete)
        case _          => Left(BadRequest(errorJson("invalid_field", "requestType must be 'retrieve' or 'delete'")))
      req         <- shares.openShareRequest(callerKey, shareId, requestType)
                       .left.map(domainErrorToResult)
    yield Created(shareRequestJson(req))
    result.merge
  }

  /** GET /share-requests?role=sender|recipient[&state=pending|approved|denied] — list requests. */
  def listShareRequests() = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey   <- AuthHelper.verify(request, Array.empty)
      roleStr     <- request.getQueryString("role")
                       .toRight(BadRequest(errorJson("missing_param", "role is required")))
      asSender    <- roleStr match
        case "sender"    => Right(true)
        case "recipient" => Right(false)
        case _           => Left(BadRequest(errorJson("invalid_param", "role must be 'sender' or 'recipient'")))
      stateFilter  = request.getQueryString("state").flatMap {
        case "pending"  => Some(ShareRequestState.Pending)
        case "approved" => Some(ShareRequestState.Approved)
        case "denied"   => Some(ShareRequestState.Denied)
        case _          => None
      }
      reqs        <- shares.listShareRequests(callerKey, asSender, stateFilter)
                       .left.map(domainErrorToResult)
    yield Ok(JsArray(reqs.map(shareRequestJson).toSeq))
    result.merge
  }

  /** GET /share-requests/:requestId — poll for resolution (sender) or review (recipient). */
  def getShareRequest(requestId: String) = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      id        <- parseUuid(requestId)
                     .toRight(BadRequest(errorJson("invalid_param", "requestId must be a UUID")))
      req       <- shares.getShareRequest(callerKey, id)
                     .left.map(domainErrorToResult)
    yield Ok(shareRequestJson(req))
    result.merge
  }

  /** PATCH /share-requests/:requestId — approve or deny a pending request (recipient).
    * When approving a retrieve request, the body must include `ciphertext` (base64-encoded share
    * bytes from the recipient's local storage).
    */
  def respondToShareRequest(requestId: String) = Action(parse.raw) { (request: Request[RawBuffer]) =>
    val bodyBytes = request.body.asBytes().map(_.toArray).getOrElse(Array.empty[Byte])
    val result = for
      callerKey  <- AuthHelper.verify(request, bodyBytes)
      id         <- parseUuid(requestId)
                      .toRight(BadRequest(errorJson("invalid_param", "requestId must be a UUID")))
      json       <- parseJson(bodyBytes)
      stateStr   <- (json \ "state").asOpt[String]
                      .toRight(BadRequest(errorJson("missing_field", "state is required")))
      approved   <- stateStr match
        case "approved" => Right(true)
        case "denied"   => Right(false)
        case _          => Left(BadRequest(errorJson("invalid_field", "state must be 'approved' or 'denied'")))
      ciphertext  = (json \ "ciphertext").asOpt[String].flatMap(decodeBase64)
      req        <- shares.respondToShareRequest(callerKey, id, approved, ciphertext)
                      .left.map(domainErrorToResult)
    yield Ok(shareRequestJson(req))
    result.merge
  }

  private def decodeBase64(s: String): Option[Array[Byte]] =
    try Some(java.util.Base64.getDecoder.decode(s)) catch case _: Exception => None

  private def parseJson(bytes: Array[Byte]): Either[Result, JsValue] =
    try Right(Json.parse(bytes))
    catch case e: Exception => Left(BadRequest(errorJson("invalid_json", e.getMessage)))

  private def parseUuid(s: String): Option[UUID] =
    try Some(UUID.fromString(s)) catch case _: Exception => None
