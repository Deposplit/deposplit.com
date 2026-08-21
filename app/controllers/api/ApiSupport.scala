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

import play.api.libs.json.*
import play.api.mvc.BaseController
import play.api.mvc.Result
import value_objects.CipherSuite
import value_objects.CustodyHeartbeat
import value_objects.Error
import value_objects.KeyRotation
import value_objects.ShareRequest
import value_objects.ShareRequestState
import value_objects.ShareTransactionType
import value_objects.Signature

import java.util.Base64

/** Shared JSON serialisation and error-mapping helpers for API controllers. */
trait ApiSupport { self: BaseController =>

  private val b64Enc = Base64.getEncoder

  protected def errorJson(code: String, message: String): JsValue =
    Json.obj("code" -> code, "message" -> message)

  protected def domainErrorToResult(err: Error): Result = err match
    case Error.NotFound   => NotFound(errorJson("not_found", "Resource not found"))
    case Error.Conflict   => Conflict(errorJson("conflict", "Resource conflict"))
    case Error.Forbidden  => Forbidden(errorJson("forbidden", "Access denied"))
    case Error.BadRequest => BadRequest(errorJson("bad_request", "Invalid request"))

  protected def shareRequestJson(req: ShareRequest): JsValue =
    val base = Json.obj(
      "id"               -> req.id.toString,
      "secretId"         -> req.secretId.value.toString,
      "senderKey"        -> req.senderKey.toBase64Url,
      "recipientKey"     -> req.recipientKey.toBase64Url,
      "label"            -> req.label.value,
      "secretCreatedAt"  -> req.secretCreatedAt.toString,
      "transactionType"  -> req.transactionType.wireValue,
      "state"            -> (req.state match
        case ShareRequestState.Pending   => "pending"
        case ShareRequestState.Approved  => "approved"
        case ShareRequestState.Denied    => "denied"
        case ShareRequestState.Withdrawn => "withdrawn"),
      "shareId"          -> req.shareId.map(_.toString),
      "requestedAt"      -> req.requestedAt.toString,
      "respondedAt"      -> req.respondedAt.map(_.toString),
      "senderSignature"  -> req.senderSignature.toBase64Url
    )
    val withRecipientSig =
      req.recipientSignature.fold(base)(sig => base + ("recipientSignature" -> JsString(sig.toBase64Url)))
    val withCiphertext =
      req.ciphertext.fold(withRecipientSig)(ct => withRecipientSig + ("ciphertext" -> JsString(b64Enc.encodeToString(ct))))
    val withK = req.k.fold(withCiphertext)(k => withCiphertext + ("k" -> JsNumber(k)))
    req.n.fold(withK)(n => withK + ("n" -> JsNumber(n)))

  protected def keyRotationJson(r: KeyRotation): JsValue =
    Json.obj(
      "id"             -> r.id.toString,
      "oldVerifyKey"   -> r.oldVerifyKey.toBase64Url,
      "recipientKey"   -> r.recipientKey.toBase64Url,
      "newVerifyKey"   -> r.newVerifyKey.toBase64Url,
      "newEncKey"      -> r.newEncKey.toBase64Url,
      "newCipherSuite" -> r.newCipherSuite.wireValue,
      "signature"      -> r.signature.toBase64Url,
      "createdAt"      -> r.createdAt.toString
    )

  protected def custodyHeartbeatJson(h: CustodyHeartbeat): JsValue =
    Json.obj(
      "id"        -> h.id.toString,
      "holderKey" -> h.holderKey.toBase64Url,
      "ownerKey"  -> h.ownerKey.toBase64Url,
      "secretIds" -> h.secretIds.map(_.toString),
      "optedOut"  -> h.optedOut,
      "signature" -> h.signature.toBase64Url,
      "createdAt" -> h.createdAt.toString
    )
}
