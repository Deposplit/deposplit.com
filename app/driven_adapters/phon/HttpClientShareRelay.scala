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
import value_objects.svo.CustodyHeartbeat
import value_objects.svo.KeyRotation
import value_objects.svo.Role
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareTransactionType

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

class HttpClientShareRelay @Inject() (identity: Identity, baseUrl: String = "http://localhost:9000") extends ShareRelay:

  private val httpClient = HttpClient.newHttpClient()
  private val secureRandom = SecureRandom()

  // ── ShareRelay ────────────────────────────────────────────────────────────

  override def openShareRequest(
      secretId: UUID,
      recipientKey: Array[Byte],
      label: String,
      secretCreatedAt: Instant,
      transactionType: ShareTransactionType,
      shareId: Option[UUID],
      ciphertext: Option[Array[Byte]],
      k: Option[Int] = None,
      n: Option[Int] = None,
      senderSignature: Array[Byte]
  ): ShareRequest =
    val body = Json
      .obj(
        "secretId" -> secretId.toString,
        "recipientKey" -> encodeBase64Url(recipientKey),
        "label" -> label,
        "secretCreatedAt" -> secretCreatedAt.toString,
        "transactionType" -> transactionType.wireValue,
        "senderSignature" -> encodeBase64Url(senderSignature)
      )
      .deepMerge(shareId.fold(Json.obj())(id => Json.obj("shareId" -> id.toString)))
      .deepMerge(ciphertext.fold(Json.obj())(ct => Json.obj("ciphertext" -> encodeBase64(ct))))
      .deepMerge(k.fold(Json.obj())(v => Json.obj("k" -> v)))
      .deepMerge(n.fold(Json.obj())(v => Json.obj("n" -> v)))
    parseShareRequest(send("POST", "/share-requests", Some(body)))

  override def listShareRequests(
      role: Role,
      transactionType: Option[ShareTransactionType] = None,
      state: Option[ShareRequestState] = None
  ): List[ShareRequest] =
    val q = s"?role=${role.toString.toLowerCase}" +
      transactionType.fold("")(tt => s"&type=${tt.wireValue}") +
      state.fold("")(st => s"&state=${st.toString.toLowerCase}")
    send("GET", s"/share-requests$q").as[JsArray].value.map(parseShareRequest).toList

  override def getShareRequest(requestId: UUID): ShareRequest =
    parseShareRequest(send("GET", s"/share-requests/$requestId"))

  override def respondToShareRequest(
      requestId: UUID,
      approved: Boolean,
      ciphertext: Option[Array[Byte]] = None,
      recipientSignature: Array[Byte]
  ): ShareRequest =
    val body = Json
      .obj(
        "state" -> JsString(if approved then "approved" else "denied"),
        "recipientSignature" -> encodeBase64Url(recipientSignature)
      )
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

  override def withdrawShareRequests(senderKey: Option[Array[Byte]] = None, secretId: Option[UUID] = None): Unit =
    val q = senderKey.fold("")(k => s"?senderKey=${encodeBase64Url(k)}") +
      secretId.fold("")(id => s"${if senderKey.isDefined then "&" else "?"}secretId=$id")
    send("POST", s"/share-requests/withdraw$q")
    ()

  override def pushRotation(recipientKey: Array[Byte], newEd25519Key: Array[Byte], newX25519Key: Array[Byte], signature: Array[Byte]): Unit =
    val body = Json.obj(
      "recipientKey" -> encodeBase64Url(recipientKey),
      "newEd25519Key" -> encodeBase64Url(newEd25519Key),
      "newX25519Key" -> encodeBase64Url(newX25519Key),
      "signature" -> encodeBase64Url(signature)
    )
    send("POST", "/key-rotations", Some(body))
    ()

  override def listRotations(): List[KeyRotation] =
    send("GET", "/key-rotations").as[JsArray].value.map(parseKeyRotation).toList

  override def deleteRotation(id: UUID): Unit =
    send("DELETE", s"/key-rotations/$id")
    ()

  override def pushHeartbeat(ownerKey: Array[Byte], secretIds: Seq[UUID], optedOut: Boolean, signature: Array[Byte]): Unit =
    val body = Json.obj(
      "ownerKey"  -> encodeBase64Url(ownerKey),
      "secretIds" -> secretIds.map(_.toString),
      "optedOut"  -> optedOut,
      "signature" -> encodeBase64Url(signature)
    )
    send("POST", "/custody-heartbeats", Some(body))
    ()

  override def listHeartbeats(): List[CustodyHeartbeat] =
    send("GET", "/custody-heartbeats").as[JsArray].value.map(parseCustodyHeartbeat).toList

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

  private def parseShareRequest(json: JsValue): ShareRequest =
    ShareRequest(
      id = UUID.fromString((json \ "id").as[String]),
      secretId = UUID.fromString((json \ "secretId").as[String]),
      senderKey = decodeBase64Url((json \ "senderKey").as[String]),
      recipientKey = decodeBase64Url((json \ "recipientKey").as[String]),
      label = (json \ "label").as[String],
      secretCreatedAt = Instant.parse((json \ "secretCreatedAt").as[String]),
      transactionType = ShareTransactionType
        .fromWire((json \ "transactionType").as[String])
        .getOrElse(throw IllegalArgumentException(s"Unknown transactionType: ${(json \ "transactionType").as[String]}")),
      state = (json \ "state").as[String] match
        case "pending"   => ShareRequestState.Pending
        case "approved"  => ShareRequestState.Approved
        case "denied"    => ShareRequestState.Denied
        case "withdrawn" => ShareRequestState.Withdrawn
        case other       => throw IllegalArgumentException(s"Unknown state: $other"),
      shareId = (json \ "shareId").asOpt[String].map(UUID.fromString),
      requestedAt = Instant.parse((json \ "requestedAt").as[String]),
      respondedAt = (json \ "respondedAt").asOpt[String].map(Instant.parse),
      ciphertext = (json \ "ciphertext").asOpt[String].map(decodeBase64),
      k = (json \ "k").asOpt[Int],
      n = (json \ "n").asOpt[Int],
      senderSignature = decodeBase64Url((json \ "senderSignature").as[String]),
      recipientSignature = (json \ "recipientSignature").asOpt[String].map(decodeBase64Url)
    )

  private def parseKeyRotation(json: JsValue): KeyRotation =
    KeyRotation(
      id = UUID.fromString((json \ "id").as[String]),
      oldEd25519Key = decodeBase64Url((json \ "oldEd25519Key").as[String]),
      recipientKey = decodeBase64Url((json \ "recipientKey").as[String]),
      newEd25519Key = decodeBase64Url((json \ "newEd25519Key").as[String]),
      newX25519Key = decodeBase64Url((json \ "newX25519Key").as[String]),
      signature = decodeBase64Url((json \ "signature").as[String]),
      createdAt = Instant.parse((json \ "createdAt").as[String])
    )

  private def parseCustodyHeartbeat(json: JsValue): CustodyHeartbeat =
    CustodyHeartbeat(
      id = UUID.fromString((json \ "id").as[String]),
      holderKey = decodeBase64Url((json \ "holderKey").as[String]),
      ownerKey = decodeBase64Url((json \ "ownerKey").as[String]),
      secretIds = (json \ "secretIds").as[Seq[String]].map(UUID.fromString),
      optedOut = (json \ "optedOut").as[Boolean],
      signature = decodeBase64Url((json \ "signature").as[String]),
      createdAt = Instant.parse((json \ "createdAt").as[String])
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
