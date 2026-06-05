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

package controllers.phon

import play.api.libs.json.Json
import play.api.libs.json.OFormat

import java.util.Base64

final case class QrPayload(v: Int, pseudonym: String, ed: String, x: String)

object QrPayload:

  given OFormat[QrPayload] = Json.format[QrPayload]

  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder()

  def apply(pseudonym: String, edPublicKey: Array[Byte], xPublicKey: Array[Byte]): QrPayload =
    QrPayload(
      v = 1,
      pseudonym = pseudonym,
      ed = encoder.encodeToString(edPublicKey),
      x = encoder.encodeToString(xPublicKey)
    )

  def encodeKey(key: Array[Byte]): String = encoder.encodeToString(key)

  def encode(qrPayload: QrPayload): String =
    Json.toJson(qrPayload).toString

  def encode(pseudonym: String, edPublicKey: Array[Byte], xPublicKey: Array[Byte]): String =
    encode(apply(pseudonym, edPublicKey, xPublicKey))

  def decode(raw: String): QrPayload = Json.parse(raw).as[QrPayload]

  def decodeKey(base64: String): Array[Byte] = decoder.decode(base64)
