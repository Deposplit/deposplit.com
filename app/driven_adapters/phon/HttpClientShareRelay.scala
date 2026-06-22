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

package driven_adapters.phon

import driven_ports.ShareRelay
import driving_ports.Identity
import jakarta.inject.Inject
import play.api.libs.json.*
import value_objects.svo.Role
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

class HttpClientShareRelay @Inject() (identity: Identity) extends ShareRelay:

  private val baseUrl = "http://localhost:9000"
  private val httpClient = HttpClient.newHttpClient()
  private val secureRandom = SecureRandom()

  // ── ShareRelay ────────────────────────────────────────────────────────────

  override def openShareRequest(
      secretId: UUID,
      recipientKey: Array[Byte],
      label: String,
      secretCreatedAt: Instant,
      requestType: ShareRequestType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]]
  ): ShareRequest =
    val body = Json
      .obj(
        "secretId" -> secretId.toString,
        "recipientKey" -> encodeBase64Url(recipientKey),
        "label" -> label,
        "secretCreatedAt" -> secretCreatedAt.toString,
        "requestType" -> requestTypeStr(requestType)
      )
      .deepMerge(shareId.fold(Json.obj())(id => Json.obj("shareId" -> id.toString)))
      .deepMerge(ciphertext.fold(Json.obj())(ct => Json.obj("ciphertext" -> encodeBase64(ct))))
    parseShareRequest(send("POST", "/share-requests", Some(body)))

  override def listShareRequests(
      role: Role,
      requestType: Option[ShareRequestType] = None,
      state: Option[ShareRequestState] = None
  ): List[ShareRequest] =
    val q = s"?role=${role.toString.toLowerCase}" +
      requestType.fold("")(rt => s"&type=${requestTypeStr(rt)}") +
      state.fold("")(st => s"&state=${st.toString.toLowerCase}")
    send("GET", s"/share-requests$q").as[JsArray].value.map(parseShareRequest).toList

  override def getShareRequest(requestId: UUID): ShareRequest =
    parseShareRequest(send("GET", s"/share-requests/$requestId"))

  override def respondToShareRequest(
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]] = None
  ): ShareRequest =
    val body = Json
      .obj("state" -> JsString(if approved then "approved" else "denied"))
      .deepMerge(ciphertext.fold(Json.obj())(ct => Json.obj("ciphertext" -> encodeBase64(ct))))
    parseShareRequest(send("PATCH", s"/share-requests/$requestId", Some(body)))

  override def deleteShareRequest(requestId: UUID): Unit =
    send("DELETE", s"/share-requests/$requestId")
    ()

  override def deleteShareRequests(senderKey: Option[Array[Byte]], secretId: Option[UUID]): Unit =
    val q = senderKey.fold("")(k => s"?senderKey=${encodeBase64Url(k)}") +
      secretId.fold("")(id => s"${if senderKey.isDefined then "&" else "?"}secretId=$id")
    send("DELETE", s"/share-requests$q")
    ()

  // ── HTTP ──────────────────────────────────────────────────────────────────

  private def send(method: String, path: String, body: Option[JsValue] = None): JsValue =
    val bodyBytes = body.fold(Array.emptyByteArray)(_.toString.getBytes("UTF-8"))
    val nonce = generateNonce()
    val canonical = s"$nonce\n${method.toUpperCase}\n$path\n${sha256Hex(bodyBytes)}"
    val sig = identity.sign(canonical.getBytes("UTF-8"))

    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Accept", "application/json")
      .header("X-Deposplit-Public-Key", encodeBase64Url(identity.edPublicKey()))
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

  // ── JSON ──────────────────────────────────────────────────────────────────

  private def requestTypeStr(rt: ShareRequestType): String = rt match
    case ShareRequestType.PickUp   => "pick_up"
    case ShareRequestType.Retrieve => "retrieve"
    case ShareRequestType.Delete   => "delete"

  private def parseShareRequest(json: JsValue): ShareRequest =
    ShareRequest(
      id = UUID.fromString((json \ "id").as[String]),
      secretId = UUID.fromString((json \ "secretId").as[String]),
      senderKey = decodeBase64Url((json \ "senderKey").as[String]),
      recipientKey = decodeBase64Url((json \ "recipientKey").as[String]),
      label = (json \ "label").as[String],
      secretCreatedAt = Instant.parse((json \ "secretCreatedAt").as[String]),
      requestType = (json \ "requestType").as[String] match
        case "pick_up"  => ShareRequestType.PickUp
        case "retrieve" => ShareRequestType.Retrieve
        case "delete"   => ShareRequestType.Delete
        case other      => throw IllegalArgumentException(s"Unknown requestType: $other"),
      state = (json \ "state").as[String] match
        case "pending"  => ShareRequestState.Pending
        case "approved" => ShareRequestState.Approved
        case "denied"   => ShareRequestState.Denied
        case other      => throw IllegalArgumentException(s"Unknown state: $other"),
      shareId = (json \ "shareId").asOpt[String].map(UUID.fromString),
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
