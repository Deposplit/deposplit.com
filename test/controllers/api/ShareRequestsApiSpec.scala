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

import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import play.api.libs.json.*
import play.api.test.*
import play.api.test.Helpers.*

import java.util.Base64
import java.util.UUID

/** Integration tests for POST/GET/PATCH/DELETE /share-requests.
  *
  * Tests run in declaration order and share a single in-memory H2 database via GuiceOneAppPerSuite. Each suite uses
  * fresh keypairs so data is naturally isolated from other suites even when the DB is shared across the test JVM.
  *
  * Every POST/PATCH body carries a `senderSignature`/`recipientSignature` computed via
  * `RequestSigner.signOpen`/`signRespond`, mirroring `PayloadCanonical` — see that object for the exact canonical-bytes
  * construction being signed here.
  */
class ShareRequestsApiSpec extends PlaySpec with GuiceOneAppPerSuite:

  private val alice = new RequestSigner()
  private val bob = new RequestSigner()
  private val secretId = UUID.randomUUID().toString
  private val createdAt = "2026-01-01T00:00:00Z"

  // Populated by tests; used by subsequent tests in declaration order.
  private var depositId: String = ""
  private var retrievalId: String = ""

  // ── Body builders ──────────────────────────────────────────────────────────

  private def depositBody(
      sender: RequestSigner,
      recipient: RequestSigner,
      sid: String = secretId,
      ct: String = "AQID",
      k: Int = 2,
      n: Int = 3,
      mime: String = "text/plain"
  ): Array[Byte] =
    val sig = sender.signOpen(
      sid,
      "deposit",
      recipient.publicKeyHeader,
      "test secret",
      createdAt,
      None,
      Some(ct),
      Some(k),
      Some(n),
      Some(mime)
    )
    s"""{
       |  "transactionType": "deposit",
       |  "secretId":        "$sid",
       |  "label":           "test secret",
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "secretCreatedAt": "$createdAt",
       |  "ciphertext":      "$ct",
       |  "k":               $k,
       |  "n":               $n,
       |  "mimeType":        "$mime",
       |  "senderSignature": "$sig"
       |}""".stripMargin.getBytes("UTF-8")

  private def retrievalBody(
      shareId: String,
      recipient: RequestSigner,
      sender: RequestSigner = alice
  ): Array[Byte] =
    val sig =
      sender.signOpen(secretId, "retrieval", recipient.publicKeyHeader, "test secret", createdAt, Some(shareId), None)
    s"""{
       |  "transactionType": "retrieval",
       |  "secretId":        "$secretId",
       |  "label":           "test secret",
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "secretCreatedAt": "$createdAt",
       |  "shareId":         "$shareId",
       |  "senderSignature": "$sig"
       |}""".stripMargin.getBytes("UTF-8")

  private def removalBody(
      shareId: String,
      recipient: RequestSigner,
      sender: RequestSigner = alice
  ): Array[Byte] =
    val sig =
      sender.signOpen(secretId, "removal", recipient.publicKeyHeader, "test secret", createdAt, Some(shareId), None)
    s"""{
       |  "transactionType": "removal",
       |  "secretId":        "$secretId",
       |  "label":           "test secret",
       |  "recipientKey":    "${recipient.publicKeyHeader}",
       |  "secretCreatedAt": "$createdAt",
       |  "shareId":         "$shareId",
       |  "senderSignature": "$sig"
       |}""".stripMargin.getBytes("UTF-8")

  private def respondBody(
      signer: RequestSigner,
      requestId: String,
      approved: Boolean,
      ciphertext: Option[String] = None
  ): Array[Byte] =
    val sig = signer.signRespond(requestId, approved, ciphertext)
    val ciphertextField = ciphertext.fold("")(ct => s""","ciphertext":"$ct"""")
    s"""{"state":"${if approved then "approved" else "denied"}","recipientSignature":"$sig"$ciphertextField}"""
      .getBytes("UTF-8")

  // ── Deposit flow ───────────────────────────────────────────────────────────

  "POST /share-requests (Deposit)" should {

    "deposit a share and return a Deposit ShareRequest with all required fields" in {
      val result = route(app, alice.post("/share-requests", depositBody(alice, bob))).get
      status(result) mustBe CREATED
      contentType(result) mustBe Some("application/json")
      val json = contentAsJson(result)
      depositId = (json \ "id").as[String]
      depositId must not be empty
      (json \ "transactionType").as[String] mustBe "deposit"
      (json \ "state").as[String] mustBe "pending"
      (json \ "secretId").as[String] mustBe secretId
      (json \ "label").as[String] mustBe "test secret"
      (json \ "senderKey").as[String] mustBe alice.publicKeyHeader
      (json \ "recipientKey").as[String] mustBe bob.publicKeyHeader
      (json \ "secretCreatedAt").asOpt[String] must not be empty
      (json \ "requestedAt").asOpt[String] must not be empty
      (json \ "respondedAt").asOpt[String] mustBe None
      (json \ "shareId").asOpt[String] mustBe None
      (json \ "senderSignature").asOpt[String] must not be empty
      (json \ "recipientSignature").asOpt[String] mustBe None
      // ciphertext not returned on Deposit creation (only on approval)
      (json \ "ciphertext").asOpt[String] mustBe None
      (json \ "k").as[Int] mustBe 2
      (json \ "n").as[Int] mustBe 3
      (json \ "mimeType").as[String] mustBe "text/plain"
    }

    "reject a Deposit with missing k/n" in {
      val sid = UUID.randomUUID().toString
      val sig =
        alice.signOpen(
          sid,
          "deposit",
          bob.publicKeyHeader,
          "x",
          createdAt,
          None,
          Some("AQID"),
          None,
          None,
          Some("text/plain")
        )
      val body =
        s"""{"transactionType":"deposit","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","mimeType":"text/plain","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a Deposit with k > n" in {
      val sid = UUID.randomUUID().toString
      val sig =
        alice.signOpen(
          sid,
          "deposit",
          bob.publicKeyHeader,
          "x",
          createdAt,
          None,
          Some("AQID"),
          Some(5),
          Some(3),
          Some("text/plain")
        )
      val body =
        s"""{"transactionType":"deposit","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","k":5,"n":3,"mimeType":"text/plain","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a Deposit with a missing mimeType" in {
      val sid = UUID.randomUUID().toString
      val sig =
        alice.signOpen(sid, "deposit", bob.publicKeyHeader, "x", createdAt, None, Some("AQID"), Some(2), Some(3))
      val body =
        s"""{"transactionType":"deposit","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","k":2,"n":3,"senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    // A blank mimeType signs to the same canonical bytes as an absent one, so the signature
    // verifies and only the domain check stands between it and a stored row.
    "reject a Deposit with a blank mimeType" in {
      val sid = UUID.randomUUID().toString
      val sig =
        alice.signOpen(
          sid,
          "deposit",
          bob.publicKeyHeader,
          "x",
          createdAt,
          None,
          Some("AQID"),
          Some(2),
          Some(3),
          Some("")
        )
      val body =
        s"""{"transactionType":"deposit","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","k":2,"n":3,"mimeType":"","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a duplicate active Deposit for the same (secretId, recipientKey)" in {
      val result = route(app, alice.post("/share-requests", depositBody(alice, bob))).get
      status(result) mustBe CONFLICT
    }

    "reject a Deposit without ciphertext" in {
      val sid = UUID.randomUUID().toString
      val sig = alice.signOpen(sid, "deposit", bob.publicKeyHeader, "x", createdAt, None, None)
      val body =
        s"""{"transactionType":"deposit","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "reject a Deposit with a senderSignature that doesn't verify" in {
      val sid = UUID.randomUUID().toString
      // Signed by bob instead of the caller (alice) — verifies against the wrong key.
      val sig = bob.signOpen(sid, "deposit", bob.publicKeyHeader, "x", createdAt, None, Some("AQID"))
      val body =
        s"""{"transactionType":"deposit","secretId":"$sid","label":"x","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, alice.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }
  }

  // ── List / Get ─────────────────────────────────────────────────────────────

  "GET /share-requests" should {

    "list the Deposit when queried as sender with type=deposit" in {
      val result = route(app, alice.get("/share-requests?role=sender&type=deposit")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr.exists(j => (j \ "id").as[String] == depositId) mustBe true
    }

    "list the Deposit when queried as recipient" in {
      val result = route(app, bob.get("/share-requests?role=recipient")).get
      status(result) mustBe OK
      val arr = contentAsJson(result).as[JsArray].value
      arr.exists(j => (j \ "id").as[String] == depositId) mustBe true
    }

    "return an empty list for a key with no requests" in {
      val stranger = new RequestSigner()
      val result = route(app, stranger.get("/share-requests?role=recipient")).get
      status(result) mustBe OK
      contentAsJson(result).as[JsArray].value mustBe empty
    }
  }

  "GET /share-requests/:requestId" should {

    "return the Deposit to the sender" in {
      val result = route(app, alice.get(s"/share-requests/$depositId")).get
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "id").as[String] mustBe depositId
      (json \ "state").as[String] mustBe "pending"
    }

    "return the Deposit to the recipient" in {
      val result = route(app, bob.get(s"/share-requests/$depositId")).get
      status(result) mustBe OK
      (contentAsJson(result) \ "id").as[String] mustBe depositId
    }

    "reject access by an unrelated third party" in {
      val stranger = new RequestSigner()
      val result = route(app, stranger.get(s"/share-requests/$depositId")).get
      status(result) mustBe FORBIDDEN
    }
  }

  // ── Approve Deposit (Bob collects the share) ────────────────────────────────

  "PATCH /share-requests/:requestId (approve Deposit)" should {

    "reject a response from the sender (who is not the recipient)" in {
      // alice signs with her own key (valid w.r.t. herself) — the Forbidden check is about
      // req.recipientKey vs the caller, not about the signature itself.
      val result =
        route(app, alice.patch(s"/share-requests/$depositId", respondBody(alice, depositId, approved = true))).get
      status(result) mustBe FORBIDDEN
    }

    "reject a response with a recipientSignature that doesn't verify" in {
      // bob's stated approval, but signed by alice's key — fails verification before Forbidden/Conflict checks.
      val body = respondBody(alice, depositId, approved = true)
      val result = route(app, bob.patch(s"/share-requests/$depositId", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "allow the recipient to approve and receive the ciphertext (one-time delivery)" in {
      val body = respondBody(bob, depositId, approved = true)
      val result = route(app, bob.patch(s"/share-requests/$depositId", body)).get
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "state").as[String] mustBe "approved"
      (json \ "respondedAt").asOpt[String] must not be empty
      (json \ "ciphertext").asOpt[String] must not be empty
      (json \ "recipientSignature").asOpt[String] must not be empty
    }

    "reject a second response to an already-decided Deposit" in {
      val body = respondBody(bob, depositId, approved = false)
      val result = route(app, bob.patch(s"/share-requests/$depositId", body)).get
      status(result) mustBe CONFLICT
    }

    "return no ciphertext when fetching an already-approved Deposit" in {
      val result = route(app, alice.get(s"/share-requests/$depositId")).get
      status(result) mustBe OK
      // ciphertext was cleared from the relay after delivery
      (contentAsJson(result) \ "ciphertext").asOpt[String] mustBe None
    }

    "still block re-deposit after approval (Deposit still active)" in {
      val result = route(app, alice.post("/share-requests", depositBody(alice, bob))).get
      status(result) mustBe CONFLICT
    }
  }

  // ── Retrieval flow ─────────────────────────────────────────────────────────

  "POST /share-requests (Retrieval)" should {

    "open a Retrieval request and return a pending ShareRequest" in {
      val result = route(app, alice.post("/share-requests", retrievalBody(depositId, bob))).get
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      retrievalId = (json \ "id").as[String]
      retrievalId must not be empty
      (json \ "transactionType").as[String] mustBe "retrieval"
      (json \ "state").as[String] mustBe "pending"
      (json \ "shareId").as[String] mustBe depositId
      (json \ "requestedAt").asOpt[String] must not be empty
      (json \ "respondedAt").asOpt[String] mustBe None
      (json \ "ciphertext").asOpt[String] mustBe None
    }

    "reject a duplicate pending Retrieval for the same (secretId, senderKey, recipientKey)" in {
      val result = route(app, alice.post("/share-requests", retrievalBody(depositId, bob))).get
      status(result) mustBe CONFLICT
    }
  }

  "PATCH /share-requests/:requestId (respond to Retrieval)" should {

    "reject approval of a Retrieval without ciphertext" in {
      val body = respondBody(bob, retrievalId, approved = true)
      val result = route(app, bob.patch(s"/share-requests/$retrievalId", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "allow the recipient to approve a Retrieval by supplying ciphertext" in {
      val body = respondBody(bob, retrievalId, approved = true, ciphertext = Some("AQID"))
      val result = route(app, bob.patch(s"/share-requests/$retrievalId", body)).get
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "state").as[String] mustBe "approved"
      (json \ "respondedAt").asOpt[String] must not be empty
      (json \ "ciphertext").as[String] mustBe "AQID"
    }

    "reject a second response to an already-approved Retrieval" in {
      val body = respondBody(bob, retrievalId, approved = false)
      val result = route(app, bob.patch(s"/share-requests/$retrievalId", body)).get
      status(result) mustBe CONFLICT
    }
  }

  // ── Removal flow ───────────────────────────────────────────────────────────

  "POST /share-requests (Removal) + PATCH (approve Removal)" should {

    "open a Removal request and, when approved, cascade-delete Deposit + Retrieval rows" in {
      // Open a fresh Deposit in a fresh secretId so this doesn't interfere with prior state
      val freshSecretId = UUID.randomUUID().toString
      val freshDepositSig =
        alice.signOpen(
          freshSecretId,
          "deposit",
          bob.publicKeyHeader,
          "cascade test",
          createdAt,
          None,
          Some("AQID"),
          Some(2),
          Some(3),
          Some("text/plain")
        )
      val freshDepositBody =
        s"""{"transactionType":"deposit","secretId":"$freshSecretId","label":"cascade test","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","k":2,"n":3,"mimeType":"text/plain","senderSignature":"$freshDepositSig"}"""
          .getBytes("UTF-8")
      val deposit2result = route(app, alice.post("/share-requests", freshDepositBody)).get
      status(deposit2result) mustBe CREATED
      val deposit2Id = (contentAsJson(deposit2result) \ "id").as[String]

      // Open a Removal request
      val removalSig =
        alice.signOpen(freshSecretId, "removal", bob.publicKeyHeader, "cascade test", createdAt, Some(deposit2Id), None)
      val removalReqBody =
        s"""{"transactionType":"removal","secretId":"$freshSecretId","label":"cascade test","recipientKey":"${bob.publicKeyHeader}","secretCreatedAt":"$createdAt","shareId":"$deposit2Id","senderSignature":"$removalSig"}"""
          .getBytes("UTF-8")
      val removalResult = route(app, alice.post("/share-requests", removalReqBody)).get
      status(removalResult) mustBe CREATED
      val removalReqId = (contentAsJson(removalResult) \ "id").as[String]

      // Bob approves the Removal
      val approveBody = respondBody(bob, removalReqId, approved = true)
      val approveResult = route(app, bob.patch(s"/share-requests/$removalReqId", approveBody)).get
      status(approveResult) mustBe OK
      (contentAsJson(approveResult) \ "state").as[String] mustBe "approved"

      // Deposit row should be gone (cascade)
      val depositGet = route(app, alice.get(s"/share-requests/$deposit2Id")).get
      status(depositGet) mustBe NOT_FOUND
    }
  }

  // ── Single DELETE ──────────────────────────────────────────────────────────

  "DELETE /share-requests/:requestId" should {

    "allow the sender to delete a Deposit row (cascading Retrieval/Removal)" in {
      // The depositId from the main flow is still in the DB
      val result = route(app, alice.delete(s"/share-requests/$depositId")).get
      status(result) mustBe NO_CONTENT
    }

    "return 404 for an unknown request ID" in {
      val result = route(app, bob.delete(s"/share-requests/${UUID.randomUUID()}")).get
      status(result) mustBe NOT_FOUND
    }
  }

  // ── Bulk DELETE ────────────────────────────────────────────────────────────

  "DELETE /share-requests (bulk)" should {

    "remove all requests the caller holds as recipient" in {
      // Deposit a fresh share so Bob has at least one row
      val body = depositBody(alice, bob, sid = UUID.randomUUID().toString)
      val depositResult = route(app, alice.post("/share-requests", body)).get
      status(depositResult) mustBe CREATED

      val deleteResult = route(app, bob.delete("/share-requests")).get
      status(deleteResult) mustBe NO_CONTENT

      val listResult = route(app, bob.get("/share-requests?role=recipient")).get
      status(listResult) mustBe OK
      contentAsJson(listResult).as[JsArray].value mustBe empty
    }
  }

  // ── Withdraw (recipient-initiated tombstone) ────────────────────────────────

  "POST /share-requests/withdraw" should {

    "flip an approved Deposit to withdrawn instead of deleting it" in {
      val sid = UUID.randomUUID().toString
      val body = depositBody(alice, bob, sid = sid)
      val depositResult = route(app, alice.post("/share-requests", body)).get
      status(depositResult) mustBe CREATED
      val id = (contentAsJson(depositResult) \ "id").as[String]

      val approveResult = route(app, bob.patch(s"/share-requests/$id", respondBody(bob, id, approved = true))).get
      status(approveResult) mustBe OK

      val withdrawResult = route(app, bob.post(s"/share-requests/withdraw?secretId=$sid", Array.empty[Byte])).get
      status(withdrawResult) mustBe NO_CONTENT

      val getResult = route(app, alice.get(s"/share-requests/$id")).get
      status(getResult) mustBe OK
      (contentAsJson(getResult) \ "state").as[String] mustBe "withdrawn"
    }

    "leave a Pending Deposit untouched — nothing to withdraw yet" in {
      val sid = UUID.randomUUID().toString
      val body = depositBody(alice, bob, sid = sid)
      val depositResult = route(app, alice.post("/share-requests", body)).get
      status(depositResult) mustBe CREATED
      val id = (contentAsJson(depositResult) \ "id").as[String]

      val withdrawResult = route(app, bob.post(s"/share-requests/withdraw?secretId=$sid", Array.empty[Byte])).get
      status(withdrawResult) mustBe NO_CONTENT

      val getResult = route(app, alice.get(s"/share-requests/$id")).get
      (contentAsJson(getResult) \ "state").as[String] mustBe "pending"
    }

    "is a no-op (still 204) when nothing matches" in {
      val result = route(app, bob.post("/share-requests/withdraw?secretId=" + UUID.randomUUID(), Array.empty[Byte])).get
      status(result) mustBe NO_CONTENT
    }
  }

  // ── Inventory push ──────────────────────────────────────────────────────────

  "POST /share-requests (Inventory)" should {

    "self-approve on open — no consent phase, visible to the recipient immediately" in {
      val sid = UUID.randomUUID().toString
      // Bob (the holder) pushes to Alice (the recovering owner) — sender/recipient roles are
      // reversed from a Deposit: the holder is senderKey here.
      val sig = bob.signOpen(
        sid,
        "inventory",
        alice.publicKeyHeader,
        "recovered secret",
        createdAt,
        None,
        None,
        Some(2),
        Some(3),
        Some("text/plain")
      )
      val body =
        s"""{"transactionType":"inventory","secretId":"$sid","label":"recovered secret","recipientKey":"${alice.publicKeyHeader}","secretCreatedAt":"$createdAt","k":2,"n":3,"mimeType":"text/plain","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, bob.post("/share-requests", body)).get
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      val id = (json \ "id").as[String]
      (json \ "transactionType").as[String] mustBe "inventory"
      (json \ "state").as[String] mustBe "approved"
      (json \ "respondedAt").asOpt[String] must not be empty
      (json \ "recipientSignature").asOpt[String] mustBe None
      (json \ "shareId").asOpt[String] mustBe None
      (json \ "k").as[Int] mustBe 2
      (json \ "n").as[Int] mustBe 3
      (json \ "mimeType").as[String] mustBe "text/plain"

      // Alice can already see it as approved — no PATCH needed.
      val listResult = route(app, alice.get("/share-requests?role=recipient&type=inventory&state=approved")).get
      status(listResult) mustBe OK
      contentAsJson(listResult).as[JsArray].value.exists(j => (j \ "id").as[String] == id) mustBe true

      // Alice deletes it once consumed.
      val deleteResult = route(app, alice.delete(s"/share-requests/$id")).get
      status(deleteResult) mustBe NO_CONTENT
    }

    "reject an Inventory push with ciphertext" in {
      val sid = UUID.randomUUID().toString
      val sig =
        bob.signOpen(
          sid,
          "inventory",
          alice.publicKeyHeader,
          "x",
          createdAt,
          None,
          Some("AQID"),
          Some(2),
          Some(3),
          Some("text/plain")
        )
      val body =
        s"""{"transactionType":"inventory","secretId":"$sid","label":"x","recipientKey":"${alice.publicKeyHeader}","secretCreatedAt":"$createdAt","ciphertext":"AQID","k":2,"n":3,"mimeType":"text/plain","senderSignature":"$sig"}"""
          .getBytes("UTF-8")
      val result = route(app, bob.post("/share-requests", body)).get
      status(result) mustBe BAD_REQUEST
    }

    "never conflicts, even pushed repeatedly for the same secretId" in {
      val sid = UUID.randomUUID().toString
      def push() =
        val sig =
          bob.signOpen(
            sid,
            "inventory",
            alice.publicKeyHeader,
            "x",
            createdAt,
            None,
            None,
            Some(2),
            Some(3),
            Some("text/plain")
          )
        val body =
          s"""{"transactionType":"inventory","secretId":"$sid","label":"x","recipientKey":"${alice.publicKeyHeader}","secretCreatedAt":"$createdAt","k":2,"n":3,"mimeType":"text/plain","senderSignature":"$sig"}"""
            .getBytes("UTF-8")
        route(app, bob.post("/share-requests", body)).get
      status(push()) mustBe CREATED
      status(push()) mustBe CREATED
    }
  }

  // ── Request body size ──────────────────────────────────────────────────────

  "POST /share-requests body size" should {

    "accept a deposit whose ciphertext is far larger than Play's default in-memory buffer" in {
      // ~273 KiB of base64 in a ~274 KiB body — comfortably past the 100 KB `maxMemoryBuffer`
      // default. This used to fail: the raw body spooled to a temp file, `RawBuffer.asBytes`
      // answered None above the threshold, and the controller's fallback handed signature
      // verification an *empty* array, so a perfectly valid deposit came back unauthorised.
      val bigCiphertext = Base64.getEncoder.encodeToString(Array.fill(200 * 1024)(0x7a.toByte))
      val sender = new RequestSigner()
      val recipient = new RequestSigner()
      val sid = UUID.randomUUID().toString
      val result = route(app, sender.post("/share-requests", depositBody(sender, recipient, sid, bigCiphertext))).get
      status(result) mustBe CREATED
    }

    "refuse a body past the parser's limit with 413, not a misleading 400 or 401" in {
      val oversized = Array.fill(1024 * 1024 + 1024)(0x7a.toByte)
      val result = route(app, alice.post("/share-requests", oversized)).get
      status(result) mustBe REQUEST_ENTITY_TOO_LARGE
    }
  }
