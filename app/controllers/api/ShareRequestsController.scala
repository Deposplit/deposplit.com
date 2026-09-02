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

import driving_ports.ShareRequests
import jakarta.inject.*
import play.api.libs.json.*
import play.api.mvc.*
import value_objects.*

import java.time.Instant
import java.util.Base64
import java.util.UUID

class ShareRequestsController @Inject() (
    val controllerComponents: ControllerComponents,
    shares: ShareRequests
) extends BaseController,
      ApiSupport:

  private val b64Dec = Base64.getDecoder

  /** POST /share-requests — open any share request (Deposit / Retrieval / Removal / Inventory). */
  def openShareRequest() = Action(signedBody) { (request: Request[RawBuffer]) =>
    val result = for
      bodyBytes <- signedBodyBytes(request)
      callerKey <- AuthHelper.verify(request, bodyBytes)
      json <- parseJson(bodyBytes)
      ttStr <- (json \ "transactionType")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "transactionType is required")))
      transactionType <- ShareTransactionType
        .fromWire(ttStr)
        .toRight(
          BadRequest(
            errorJson("invalid_field", "transactionType must be 'deposit', 'retrieval', 'removal', or 'inventory'")
          )
        )
      secretIdStr <- (json \ "secretId")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "secretId is required")))
      secretId <- parseUuid(secretIdStr)
        .map(SecretId(_))
        .toRight(BadRequest(errorJson("invalid_field", "secretId must be a UUID")))
      labelStr <- (json \ "label")
        .asOpt[String]
        .filter(_.nonEmpty)
        .toRight(BadRequest(errorJson("missing_field", "label is required and must be non-empty")))
      rkStr <- (json \ "recipientKey")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "recipientKey is required")))
      recipientKey <- PublicKey.fromBase64Url(rkStr).left.map(e => BadRequest(errorJson("invalid_field", e)))
      createdAtStr <- (json \ "secretCreatedAt")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "secretCreatedAt is required")))
      secretCreatedAt <- parseInstant(createdAtStr)
        .toRight(BadRequest(errorJson("invalid_field", "secretCreatedAt must be a valid ISO-8601 date-time")))
      shareId = (json \ "shareId").asOpt[String].flatMap(parseUuid)
      ciphertext = (json \ "ciphertext").asOpt[String].flatMap(decodeBase64)
      k = (json \ "k").asOpt[Int]
      n = (json \ "n").asOpt[Int]
      mimeType = (json \ "mimeType").asOpt[String].map(MimeType.apply)
      sigStr <- (json \ "senderSignature")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "senderSignature is required")))
      senderSignature <- Signature.fromBase64Url(sigStr).left.map(e => BadRequest(errorJson("invalid_field", e)))
      req <- shares
        .openShareRequest(
          callerKey,
          recipientKey,
          secretId,
          Label(labelStr),
          secretCreatedAt,
          transactionType,
          shareId,
          ciphertext,
          k,
          n,
          mimeType,
          senderSignature
        )
        .left
        .map(domainErrorToResult)
    yield Created(shareRequestJson(req))
    result.merge
  }

  /** GET
    * /share-requests?role=sender|recipient[&type=deposit|retrieval|removal|inventory][&state=pending|approved|denied|withdrawn]
    */
  def listShareRequests() = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      roleStr <- request
        .getQueryString("role")
        .toRight(BadRequest(errorJson("missing_param", "role is required")))
      asSender <- roleStr match
        case "sender"    => Right(true)
        case "recipient" => Right(false)
        case _           => Left(BadRequest(errorJson("invalid_param", "role must be 'sender' or 'recipient'")))
      transactionType = request.getQueryString("type").flatMap(ShareTransactionType.fromWire)
      stateFilter = request.getQueryString("state").flatMap {
        case "pending"   => Some(ShareRequestState.Pending)
        case "approved"  => Some(ShareRequestState.Approved)
        case "denied"    => Some(ShareRequestState.Denied)
        case "withdrawn" => Some(ShareRequestState.Withdrawn)
        case _           => None
      }
      reqs <- shares.listShareRequests(callerKey, asSender, transactionType, stateFilter).left.map(domainErrorToResult)
    yield Ok(JsArray(reqs.map(shareRequestJson).toSeq))
    result.merge
  }

  /** GET /share-requests/:requestId */
  def getShareRequest(requestId: String) = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      id <- parseUuid(requestId).toRight(BadRequest(errorJson("invalid_param", "requestId must be a UUID")))
      req <- shares.getShareRequest(callerKey, id).left.map(domainErrorToResult)
    yield Ok(shareRequestJson(req))
    result.merge
  }

  /** PATCH /share-requests/:requestId — approve or deny (recipient). Approving a Deposit clears the stored ciphertext
    * and echoes it back in the response body; the echo is a convenience, not the delivery, since the recipient's own
    * listing already carries it (and must, because the sender's signature covers it). Approving a Retrieval requires
    * `ciphertext` in the request body.
    */
  def respondToShareRequest(requestId: String) = Action(signedBody) { (request: Request[RawBuffer]) =>
    val result = for
      bodyBytes <- signedBodyBytes(request)
      callerKey <- AuthHelper.verify(request, bodyBytes)
      id <- parseUuid(requestId).toRight(BadRequest(errorJson("invalid_param", "requestId must be a UUID")))
      json <- parseJson(bodyBytes)
      stateStr <- (json \ "state")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "state is required")))
      approved <- stateStr match
        case "approved" => Right(true)
        case "denied"   => Right(false)
        case _          => Left(BadRequest(errorJson("invalid_field", "state must be 'approved' or 'denied'")))
      ciphertext = (json \ "ciphertext").asOpt[String].flatMap(decodeBase64)
      sigStr <- (json \ "recipientSignature")
        .asOpt[String]
        .toRight(BadRequest(errorJson("missing_field", "recipientSignature is required")))
      recipientSignature <- Signature.fromBase64Url(sigStr).left.map(e => BadRequest(errorJson("invalid_field", e)))
      req <- shares
        .respondToShareRequest(callerKey, id, approved, ciphertext, recipientSignature)
        .left
        .map(domainErrorToResult)
    yield Ok(shareRequestJson(req))
    result.merge
  }

  /** DELETE /share-requests/:requestId — sender or recipient may delete any request they are party to. */
  def deleteShareRequest(requestId: String) = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      id <- parseUuid(requestId).toRight(BadRequest(errorJson("invalid_param", "requestId must be a UUID")))
      _ <- shares.deleteShareRequestById(callerKey, id).left.map(domainErrorToResult)
    yield NoContent
    result.merge
  }

  /** DELETE /share-requests?senderKey=...&secretId=... — recipient-initiated bulk delete. */
  def deleteShareRequests() = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      senderKey = request.getQueryString("senderKey").flatMap(s => PublicKey.fromBase64Url(s).toOption)
      secretId = request.getQueryString("secretId").flatMap(parseUuid).map(SecretId(_))
      _ <- shares.deleteShareRequests(callerKey, senderKey, secretId).left.map(domainErrorToResult)
    yield NoContent
    result.merge
  }

  /** POST /share-requests/withdraw?senderKey=...&secretId=... — recipient-initiated unilateral withdrawal. Flips
    * matching approved Deposit rows to `withdrawn` instead of deleting them, so the sender's next poll can observe the
    * tombstone. Best-effort and fire-and-forget.
    */
  def withdrawShareRequests() = Action { (request: Request[AnyContent]) =>
    val result = for
      callerKey <- AuthHelper.verify(request, Array.empty)
      senderKey = request.getQueryString("senderKey").flatMap(s => PublicKey.fromBase64Url(s).toOption)
      secretId = request.getQueryString("secretId").flatMap(parseUuid).map(SecretId(_))
      _ <- shares.withdrawShareRequests(callerKey, senderKey, secretId).left.map(domainErrorToResult)
    yield NoContent
    result.merge
  }

  private def parseJson(bytes: Array[Byte]): Either[Result, JsValue] =
    try Right(Json.parse(bytes))
    catch case e: Exception => Left(BadRequest(errorJson("invalid_json", e.getMessage)))

  private def parseUuid(s: String): Option[UUID] =
    try Some(UUID.fromString(s))
    catch case _: Exception => None

  private def decodeBase64(s: String): Option[Array[Byte]] =
    try Some(b64Dec.decode(s))
    catch case _: Exception => None

  private def parseInstant(s: String): Option[Instant] =
    try Some(Instant.parse(s))
    catch case _: Exception => None
