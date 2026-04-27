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

import play.api.libs.json.*
import play.api.mvc.{BaseController, Result}
import value_objects.*
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

  protected def shareMetadataJson(meta: ShareMetadata): JsValue = Json.obj(
    "id"           -> meta.id.toString,
    "secretId"     -> meta.secretId.value.toString,
    "senderKey"    -> meta.senderKey.toBase64Url,
    "recipientKey" -> meta.recipientKey.toBase64Url,
    "label"        -> meta.label.value,
    "createdAt"    -> meta.createdAt.toString,
    "pickedUpAt"   -> meta.pickedUpAt.map(_.toString)
  )

  protected def shareRequestJson(req: ShareRequest): JsValue =
    val base = Json.obj(
      "id"          -> req.id.toString,
      "share"       -> shareMetadataJson(req.share),
      "requestType" -> (req.requestType match
        case ShareRequestType.Retrieve => "retrieve"
        case ShareRequestType.Delete   => "delete"),
      "state" -> (req.state match
        case ShareRequestState.Pending  => "pending"
        case ShareRequestState.Approved => "approved"
        case ShareRequestState.Denied   => "denied"),
      "requestedAt" -> req.createdAt.toString,
      "respondedAt" -> req.respondedAt.map(_.toString)
    )
    req.ciphertext.fold(base)(ct => base + ("ciphertext" -> JsString(b64Enc.encodeToString(ct))))
}
