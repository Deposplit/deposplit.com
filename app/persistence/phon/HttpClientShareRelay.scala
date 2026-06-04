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

package persistence.phon

import driven_ports.ShareRelay
import driving_ports.RequestSigner
import play.api.libs.json.*
import value_objects.svo.Role
import value_objects.svo.ShareMetadata
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareRequestType

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import jakarta.inject.Inject

class HttpClientShareRelay @Inject() (signer: RequestSigner) extends ShareRelay:

  private val baseUrl = "http://localhost:9000"
  private val httpClient = HttpClient.newHttpClient()
  private val secureRandom = SecureRandom()

  // ── ShareRelay ────────────────────────────────────────────────────────────

  override def depositShare(
      secretId: UUID,
      label: String,
      recipientKey: Array[Byte],
      ciphertext: Array[Byte]
  ): ShareMetadata =
    val body = Json.obj(
      "secretId" -> secretId.toString,
      "label" -> label,
      "recipientKey" -> encodeBase64Url(recipientKey),
      "ciphertext" -> encodeBase64(ciphertext)
    )
    parseShareMetadata(send("POST", "/shares", Some(body)))

  override def listShares(role: Role, counterpartyKey: Option[Array[Byte]] = None): List[ShareMetadata] =
    val q = s"?role=${role.toString.toLowerCase}" +
      counterpartyKey.fold("")(k => s"&counterpartyKey=${encodeBase64Url(k)}")
    send("GET", s"/shares$q").as[JsArray].value.map(parseShareMetadata).toList

  override def pickUpShare(shareId: UUID): Array[Byte] =
    decodeBase64((send("GET", s"/shares/$shareId") \ "ciphertext").as[String])

  override def deleteShare(shareId: UUID): Unit =
    send("DELETE", s"/shares/$shareId")
    ()

  override def openShareRequest(shareId: UUID, requestType: ShareRequestType): ShareRequest =
    val body = Json.obj(
      "shareId" -> shareId.toString,
      "requestType" -> requestType.toString.toLowerCase
    )
    parseShareRequest(send("POST", "/share-requests", Some(body)))

  override def listShareRequests(role: Role, state: Option[ShareRequestState] = None): List[ShareRequest] =
    val q = s"?role=${role.toString.toLowerCase}" +
      state.fold("")(s => s"&state=${s.toString.toLowerCase}")
    send("GET", s"/share-requests$q").as[JsArray].value.map(parseShareRequest).toList

  override def getShareRequest(requestId: UUID): ShareRequest =
    parseShareRequest(send("GET", s"/share-requests/$requestId"))

  override def respondToShareRequest(
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]] = None
  ): ShareRequest =
    val body = Json.obj(
      "state" -> JsString(if approved then "approved" else "denied"),
      "ciphertext" -> ciphertext.fold[JsValue](JsNull)(ct => JsString(encodeBase64(ct)))
    )
    parseShareRequest(send("PATCH", s"/share-requests/$requestId", Some(body)))

  // ── HTTP ──────────────────────────────────────────────────────────────────

  private def send(method: String, path: String, body: Option[JsValue] = None): JsValue =
    val bodyBytes = body.fold(Array.emptyByteArray)(_.toString.getBytes("UTF-8"))
    val nonce = generateNonce()
    val canonical = s"$nonce\n${method.toUpperCase}\n$path\n${sha256Hex(bodyBytes)}"
    val sig = signer.sign(canonical.getBytes("UTF-8"))

    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Accept", "application/json")
      .header("X-Deposplit-Public-Key", encodeBase64Url(signer.edPublicKey()))
      .header("X-Deposplit-Nonce", nonce)
      .header("X-Deposplit-Signature", encodeBase64Url(sig))

    val request = body match
      case Some(_) =>
        builder
          .header("Content-Type", "application/json")
          .method(method.toUpperCase, BodyPublishers.ofByteArray(bodyBytes))
          .build()
      case None =>
        builder
          .method(method.toUpperCase, BodyPublishers.noBody())
          .build()

    val response = httpClient.send(request, BodyHandlers.ofString())
    val status = response.statusCode()
    if status >= 400 then throw RuntimeException(s"HTTP $status: ${response.body()}")
    if status == 204 || response.body().isEmpty then JsNull
    else Json.parse(response.body())

  private def generateNonce(): String =
    val bytes = Array.ofDim[Byte](8)
    secureRandom.nextBytes(bytes)
    s"${System.currentTimeMillis()}.${bytes.map("%02x".format(_)).mkString}"

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  // ── JSON parsing ──────────────────────────────────────────────────────────

  private def parseShareMetadata(json: JsValue): ShareMetadata =
    ShareMetadata(
      id = UUID.fromString((json \ "id").as[String]),
      secretId = UUID.fromString((json \ "secretId").as[String]),
      label = (json \ "label").as[String],
      senderKey = decodeBase64Url((json \ "senderKey").as[String]),
      recipientKey = decodeBase64Url((json \ "recipientKey").as[String]),
      createdAt = Instant.parse((json \ "createdAt").as[String]),
      pickedUpAt = (json \ "pickedUpAt").asOpt[String].map(Instant.parse)
    )

  private def parseShareRequest(json: JsValue): ShareRequest =
    ShareRequest(
      id = UUID.fromString((json \ "id").as[String]),
      share = parseShareMetadata((json \ "share").as[JsValue]),
      requestType = (json \ "requestType").as[String] match
        case "retrieve" => ShareRequestType.Retrieve
        case "delete"   => ShareRequestType.Delete
        case other      => throw IllegalArgumentException(s"Unknown requestType: $other"),
      state = (json \ "state").as[String] match
        case "pending"  => ShareRequestState.Pending
        case "approved" => ShareRequestState.Approved
        case "denied"   => ShareRequestState.Denied
        case other      => throw IllegalArgumentException(s"Unknown state: $other"),
      requestedAt = Instant.parse((json \ "requestedAt").as[String]),
      respondedAt = (json \ "respondedAt").asOpt[String].map(Instant.parse),
      ciphertext = (json \ "ciphertext").asOpt[String].map(decodeBase64)
    )

  // ── Base64 ────────────────────────────────────────────────────────────────

  private def encodeBase64Url(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def encodeBase64(bytes: Array[Byte]): String =
    Base64.getEncoder.encodeToString(bytes)

  private def decodeBase64Url(s: String): Array[Byte] =
    Base64.getUrlDecoder.decode(s)

  private def decodeBase64(s: String): Array[Byte] =
    Base64.getDecoder.decode(s)
