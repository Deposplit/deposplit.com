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
import value_objects.svo.CipherSuite

import java.util.Base64

/** `relay` carries the *displaying* device's currently-configured relay — the out-of-band exchange mechanism BYOR uses
  * (deposplit.com/CLAUDE.md "BYOR"). `None`/absent means "use the scanning device's own default relay". `cipherSuite`
  * (item 14 — "crypto agility") is required, not optional like `relay`: every contact-exchange has exactly one cipher
  * suite in effect. This payload is rendered into a real scannable QR code (`readQrCode`) that the Android/iOS apps
  * read, so its shape must stay byte-for-byte compatible with their own `QrPayload`. Field names spelled out in full
  * (`verifyKey`/`encKey`, not `ed`/`x`), matching the vocabulary used everywhere else in the codebase.
  *
  * `v` stays at 1 permanently — Deposplit is pre-launch and never supports decoding an old shape, so a version number
  * never actually gates anything: a payload missing a newly-required field (like `cipherSuite` here) already fails to
  * decode on its own, regardless of what `v` says. Bumping `v` on every field addition would be version-tracking
  * ceremony with no compatibility matrix behind it to justify it.
  */
final case class QrPayload(
    v: Int,
    pseudonym: String,
    verifyKey: String,
    encKey: String,
    relay: Option[String] = None,
    cipherSuite: String
)

object QrPayload:

  given OFormat[QrPayload] = Json.format[QrPayload]

  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder()

  def apply(
      pseudonym: String,
      verifyKey: Array[Byte],
      encKey: Array[Byte],
      relayBaseUrl: Option[String],
      cipherSuite: CipherSuite
  ): QrPayload =
    QrPayload(
      v = 1,
      pseudonym = pseudonym,
      verifyKey = encoder.encodeToString(verifyKey),
      encKey = encoder.encodeToString(encKey),
      relay = relayBaseUrl,
      cipherSuite = cipherSuite.wireValue
    )

  def apply(pseudonym: String, verifyKey: Array[Byte], encKey: Array[Byte], relayBaseUrl: Option[String]): QrPayload =
    apply(pseudonym, verifyKey, encKey, relayBaseUrl, CipherSuite.current)

  def encodeKey(key: Array[Byte]): String = encoder.encodeToString(key)

  def encode(qrPayload: QrPayload): String =
    Json.toJson(qrPayload).toString

  def encode(
      pseudonym: String,
      verifyKey: Array[Byte],
      encKey: Array[Byte],
      relayBaseUrl: Option[String],
      cipherSuite: CipherSuite
  ): String =
    encode(apply(pseudonym, verifyKey, encKey, relayBaseUrl, cipherSuite))

  def encode(pseudonym: String, verifyKey: Array[Byte], encKey: Array[Byte], relayBaseUrl: Option[String]): String =
    encode(pseudonym, verifyKey, encKey, relayBaseUrl, CipherSuite.current)

  def decode(raw: String): QrPayload = Json.parse(raw).as[QrPayload]

  def decodeKey(base64: String): Array[Byte] = decoder.decode(base64)
