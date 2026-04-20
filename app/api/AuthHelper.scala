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

import play.api.libs.json.Json
import play.api.mvc.{RequestHeader, Result, Results}
import value_objects.{Nonce, PublicKey, Signature}
import java.security.MessageDigest

/** Verifies the three Ed25519 auth headers on every API request.
  *
  * The canonical signing string is:
  * {{{
  *   nonce "\n" UPPERCASE(method) "\n" path_with_query "\n" hex(SHA-256(body))
  * }}}
  * For body-less requests (GET, DELETE) pass `bodyBytes = Array.empty`.
  */
object AuthHelper extends Results:

  private val PublicKeyHeader = "X-Deposplit-Public-Key"
  private val NonceHeader     = "X-Deposplit-Nonce"
  private val SignatureHeader  = "X-Deposplit-Signature"

  def verify(request: RequestHeader, bodyBytes: Array[Byte]): Either[Result, PublicKey] =
    for
      pkStr  <- request.headers.get(PublicKeyHeader)
                  .toRight(Unauthorized(err("missing_header", s"$PublicKeyHeader is required")))
      nStr   <- request.headers.get(NonceHeader)
                  .toRight(Unauthorized(err("missing_header", s"$NonceHeader is required")))
      sigStr <- request.headers.get(SignatureHeader)
                  .toRight(Unauthorized(err("missing_header", s"$SignatureHeader is required")))
      pk     <- PublicKey.fromBase64Url(pkStr).left.map(e => BadRequest(err("invalid_header", e)))
      nonce  <- Nonce(nStr).toRight(BadRequest(err("invalid_header", s"$NonceHeader must be in <unix-ms>.<random> format")))
      _      <- Either.cond(!nonce.isExpired, (), Unauthorized(err("expired_nonce", "Nonce timestamp is outside the 5-minute window")))
      sig    <- Signature.fromBase64Url(sigStr).left.map(e => BadRequest(err("invalid_header", e)))
      canon   = canonical(nonce, request.method, request.uri, bodyBytes)
      _      <- Either.cond(pk.verify(canon, sig), (), Unauthorized(err("invalid_signature", "Signature verification failed")))
    yield pk

  private def canonical(nonce: Nonce, method: String, uri: String, bodyBytes: Array[Byte]): Array[Byte] =
    s"${nonce.value}\n${method.toUpperCase}\n$uri\n${sha256Hex(bodyBytes)}".getBytes("UTF-8")

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  private def err(code: String, message: String) =
    Json.obj("code" -> code, "message" -> message)
