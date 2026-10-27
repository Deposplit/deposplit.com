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

package jobs

import anorm.*
import controllers.api.RequestSigner
import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import play.api.db.Database
import play.api.libs.json.*
import play.api.test.*
import play.api.test.Helpers.*

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** RetentionSweep against the real Anorm repository and the in-memory H2 database, with the retention periods from
  * application.conf. test.conf turns the timer off, so each test sweeps by hand.
  */
class RetentionSweepSpec extends PlaySpec with GuiceOneAppPerSuite:

  private def pushHeartbeat(holder: RequestSigner, owner: RequestSigner): String =
    val secretId = UUID.randomUUID().toString
    val emittedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString
    val sig = holder.signHeartbeat(owner.publicKeyHeader, Seq(secretId), false, emittedAt)
    val body =
      s"""{"ownerKey":"${owner.publicKeyHeader}","secretIds":["$secretId"],"optedOut":false,"emittedAt":"$emittedAt","signature":"$sig"}"""
        .getBytes("UTF-8")
    val result = route(app, holder.post("/custody-heartbeats", body)).get
    status(result) mustBe CREATED
    (contentAsJson(result) \ "id").as[String]

  private def pushRotation(oldKey: RequestSigner, recipient: RequestSigner): String =
    val newKey = new RequestSigner()
    val cipherSuite = "ed25519+x25519-v1"
    // Any 32 bytes will do for the new encryption key: the relay checks its length, not what it is.
    val newEncKey = new RequestSigner().publicKeyHeader
    val sig = oldKey.signRotation(recipient.publicKeyHeader, newKey.publicKeyHeader, newEncKey, cipherSuite)
    val body =
      s"""{"recipientKey":"${recipient.publicKeyHeader}","newVerifyKey":"${newKey.publicKeyHeader}","newEncKey":"$newEncKey","newCipherSuite":"$cipherSuite","signature":"$sig"}"""
        .getBytes("UTF-8")
    val result = route(app, oldKey.post("/key-rotations", body)).get
    status(result) mustBe CREATED
    (contentAsJson(result) \ "id").as[String]

  /** Backdates a row's receipt, as if the relay had received it that long ago. */
  private def receivedAgo(table: String, id: String, ago: Duration): Unit =
    app.injector.instanceOf[Database].withConnection { implicit conn =>
      SQL(s"UPDATE $table SET created_at = {createdAt} WHERE id = {id}::uuid")
        .on("createdAt" -> Instant.now().minus(ago), "id" -> id)
        .executeUpdate()
    }

  private def idsListed(path: String, addressee: RequestSigner): Seq[String] =
    contentAsJson(route(app, addressee.get(path)).get)
      .as[JsArray]
      .value
      .map(j => (j \ "id").as[String])
      .toSeq

  private def sweep(): Unit = app.injector.instanceOf[RetentionSweep].sweep()

  "RetentionSweep.sweep" should {

    "expire a heartbeat last pushed more than 28 days ago, and keep a fresh one" in {
      val alice = new RequestSigner()
      val stale = pushHeartbeat(new RequestSigner(), alice)
      val fresh = pushHeartbeat(new RequestSigner(), alice)
      receivedAgo("custody_heartbeats", stale, Duration.ofDays(31))

      sweep()

      idsListed("/custody-heartbeats", alice) mustBe Seq(fresh)
    }

    "keep a heartbeat last pushed less than 28 days ago" in {
      val alice = new RequestSigner()
      val unread = pushHeartbeat(new RequestSigner(), alice)
      receivedAgo("custody_heartbeats", unread, Duration.ofDays(27))

      sweep()

      idsListed("/custody-heartbeats", alice) mustBe Seq(unread)
    }

    "expire a rotation received more than 180 days ago, and keep a fresh one" in {
      val bob = new RequestSigner()
      val stale = pushRotation(new RequestSigner(), bob)
      val fresh = pushRotation(new RequestSigner(), bob)
      receivedAgo("key_rotations", stale, Duration.ofDays(181))

      sweep()

      idsListed("/key-rotations", bob) mustBe Seq(fresh)
    }

    "keep a rotation received less than 180 days ago" in {
      val bob = new RequestSigner()
      val unread = pushRotation(new RequestSigner(), bob)
      receivedAgo("key_rotations", unread, Duration.ofDays(179))

      sweep()

      idsListed("/key-rotations", bob) mustBe Seq(unread)
    }
  }
