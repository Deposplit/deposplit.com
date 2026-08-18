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

package driven_adapters.persistence

import anorm.*
import anorm.SqlParser.*
import driven_ports.persistence.ShareRepository
import jakarta.inject.*
import play.api.db.Database
import value_objects.*

import java.time.Instant
import java.util.UUID

@Singleton
class AnormShareRepository @Inject() (db: Database) extends ShareRepository:

  // ---------------------------------------------------------------------------
  // Instant conversion — H2 returns TIMESTAMP WITH TIME ZONE as OffsetDateTime
  // ---------------------------------------------------------------------------

  private given Column[Instant] = Column.nonNull { (value, meta) =>
    value match
      case ts: java.sql.Timestamp        => Right(ts.toInstant)
      case odt: java.time.OffsetDateTime => Right(odt.toInstant)
      case d: java.util.Date             => Right(d.toInstant)
      case _                             =>
        Left(
          TypeDoesNotMatch(
            s"Cannot convert $value: ${value.getClass.getName} to Instant for column ${meta.column}"
          )
        )
  }

  private def parseKey(bytes: Array[Byte]): PublicKey =
    PublicKey.fromBytes(bytes).getOrElse(sys.error(s"corrupt public key in DB (${bytes.length} bytes)"))

  private def parseSignature(bytes: Array[Byte]): Signature =
    Signature.fromBytes(bytes).getOrElse(sys.error(s"corrupt signature in DB (${bytes.length} bytes)"))

  // ---------------------------------------------------------------------------
  // Row parser
  // ---------------------------------------------------------------------------

  private val shareRequestParser: RowParser[ShareRequest] =
    get[UUID]("id") ~
      get[UUID]("secret_id") ~
      get[Array[Byte]]("sender_key") ~
      get[Array[Byte]]("recipient_key") ~
      get[String]("label") ~
      get[Instant]("secret_created_at") ~
      get[String]("transaction_type") ~
      get[String]("state") ~
      get[Option[UUID]]("share_id") ~
      get[Instant]("requested_at") ~
      get[Option[Instant]]("responded_at") ~
      get[Option[Array[Byte]]]("ciphertext") ~
      get[Option[Int]]("k") ~
      get[Option[Int]]("n") ~
      get[Array[Byte]]("sender_signature") ~
      get[Option[Array[Byte]]]("recipient_signature") map {
        case id ~ sid ~ sk ~ rk ~ lbl ~ sca ~ tt ~ st ~ shId ~ reqAt ~ resAt ~ ct ~ k ~ n ~ sSig ~ rSig =>
          ShareRequest(
            id = id,
            secretId = SecretId(sid),
            senderKey = parseKey(sk),
            recipientKey = parseKey(rk),
            label = Label(lbl),
            secretCreatedAt = sca,
            transactionType =
              ShareTransactionType.fromWire(tt).getOrElse(sys.error(s"unknown transaction_type: $tt")),
            state = st match
              case "pending"   => ShareRequestState.Pending
              case "approved"  => ShareRequestState.Approved
              case "denied"    => ShareRequestState.Denied
              case "withdrawn" => ShareRequestState.Withdrawn
              case other       => sys.error(s"unknown state: $other"),
            shareId = shId,
            requestedAt = reqAt,
            respondedAt = resAt,
            ciphertext = ct,
            k = k,
            n = n,
            senderSignature = parseSignature(sSig),
            recipientSignature = rSig.map(parseSignature)
          )
      }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def stateStr(st: ShareRequestState): String = st match
    case ShareRequestState.Pending   => "pending"
    case ShareRequestState.Approved  => "approved"
    case ShareRequestState.Denied    => "denied"
    case ShareRequestState.Withdrawn => "withdrawn"

  // ---------------------------------------------------------------------------
  // ShareRepository
  // ---------------------------------------------------------------------------

  override def saveShareRequest(request: ShareRequest): Unit =
    db.withConnection { implicit conn =>
      SQL("""
        INSERT INTO share_requests
          (id, secret_id, label, sender_key, recipient_key, transaction_type, state, share_id,
           ciphertext, k, n, secret_created_at, requested_at, responded_at, sender_signature,
           recipient_signature)
        VALUES
          ({id}::uuid, {secretId}::uuid, {label}, {senderKey}, {recipientKey},
           {transactionType}, {state}, {shareId}::uuid, {ciphertext}, {k}, {n}, {secretCreatedAt},
           {requestedAt}, {respondedAt}, {senderSignature}, {recipientSignature})
      """)
        .on(
          "id" -> request.id.toString,
          "secretId" -> request.secretId.value.toString,
          "label" -> request.label.value,
          "senderKey" -> request.senderKey.toBytes,
          "recipientKey" -> request.recipientKey.toBytes,
          "transactionType" -> request.transactionType.wireValue,
          "state" -> stateStr(request.state),
          "shareId" -> request.shareId.map(_.toString).orNull,
          "ciphertext" -> request.ciphertext.orNull,
          "k" -> request.k,
          "n" -> request.n,
          "secretCreatedAt" -> request.secretCreatedAt,
          "requestedAt" -> request.requestedAt,
          "respondedAt" -> request.respondedAt,
          "senderSignature" -> request.senderSignature.toBytes,
          "recipientSignature" -> request.recipientSignature.map(_.toBytes).orNull
        )
        .executeUpdate()
    }

  override def getShareRequestById(id: UUID): Option[ShareRequest] =
    db.withConnection { implicit conn =>
      SQL("SELECT * FROM share_requests WHERE id = {id}::uuid")
        .on("id" -> id.toString)
        .as(shareRequestParser.singleOpt)
    }

  override def getShareRequestsAsSender(
      senderKey: PublicKey,
      transactionType: Option[ShareTransactionType],
      state: Option[ShareRequestState]
  ): Seq[ShareRequest] =
    db.withConnection { implicit conn =>
      (transactionType, state) match
        case (None, None) =>
          SQL("SELECT * FROM share_requests WHERE sender_key = {sk}")
            .on("sk" -> senderKey.toBytes)
            .as(shareRequestParser.*)
        case (Some(tt), None) =>
          SQL("SELECT * FROM share_requests WHERE sender_key = {sk} AND transaction_type = {tt}")
            .on("sk" -> senderKey.toBytes, "tt" -> tt.wireValue)
            .as(shareRequestParser.*)
        case (None, Some(st)) =>
          SQL("SELECT * FROM share_requests WHERE sender_key = {sk} AND state = {state}")
            .on("sk" -> senderKey.toBytes, "state" -> stateStr(st))
            .as(shareRequestParser.*)
        case (Some(tt), Some(st)) =>
          SQL("SELECT * FROM share_requests WHERE sender_key = {sk} AND transaction_type = {tt} AND state = {state}")
            .on("sk" -> senderKey.toBytes, "tt" -> tt.wireValue, "state" -> stateStr(st))
            .as(shareRequestParser.*)
    }

  override def getShareRequestsAsRecipient(
      recipientKey: PublicKey,
      transactionType: Option[ShareTransactionType],
      state: Option[ShareRequestState]
  ): Seq[ShareRequest] =
    db.withConnection { implicit conn =>
      (transactionType, state) match
        case (None, None) =>
          SQL("SELECT * FROM share_requests WHERE recipient_key = {rk}")
            .on("rk" -> recipientKey.toBytes)
            .as(shareRequestParser.*)
        case (Some(tt), None) =>
          SQL("SELECT * FROM share_requests WHERE recipient_key = {rk} AND transaction_type = {tt}")
            .on("rk" -> recipientKey.toBytes, "tt" -> tt.wireValue)
            .as(shareRequestParser.*)
        case (None, Some(st)) =>
          SQL("SELECT * FROM share_requests WHERE recipient_key = {rk} AND state = {state}")
            .on("rk" -> recipientKey.toBytes, "state" -> stateStr(st))
            .as(shareRequestParser.*)
        case (Some(tt), Some(st)) =>
          SQL("SELECT * FROM share_requests WHERE recipient_key = {rk} AND transaction_type = {tt} AND state = {state}")
            .on("rk" -> recipientKey.toBytes, "tt" -> tt.wireValue, "state" -> stateStr(st))
            .as(shareRequestParser.*)
    }

  override def hasActiveDeposit(secretId: SecretId, recipientKey: PublicKey): Boolean =
    db.withConnection { implicit conn =>
      val count = SQL("""
        SELECT COUNT(*) FROM share_requests
        WHERE secret_id = {sid}::uuid AND recipient_key = {rk}
          AND transaction_type = 'deposit' AND state NOT IN ('denied', 'withdrawn')
      """)
        .on("sid" -> secretId.value.toString, "rk" -> recipientKey.toBytes)
        .as(scalar[Long].single)
      count > 0
    }

  override def hasPendingRequest(
      secretId: SecretId,
      senderKey: PublicKey,
      recipientKey: PublicKey,
      transactionType: ShareTransactionType
  ): Boolean =
    db.withConnection { implicit conn =>
      val count = SQL("""
        SELECT COUNT(*) FROM share_requests
        WHERE secret_id = {sid}::uuid AND sender_key = {sk} AND recipient_key = {rk}
          AND transaction_type = {tt} AND state = 'pending'
      """)
        .on(
          "sid" -> secretId.value.toString,
          "sk" -> senderKey.toBytes,
          "rk" -> recipientKey.toBytes,
          "tt" -> transactionType.wireValue
        )
        .as(scalar[Long].single)
      count > 0
    }

  override def updateShareRequest(
      requestId: UUID,
      state: ShareRequestState,
      respondedAt: Instant,
      ciphertext: Option[Array[Byte]],
      recipientSignature: Signature
  ): Unit =
    db.withConnection { implicit conn =>
      SQL("""
        UPDATE share_requests
        SET state = {state}, responded_at = {respondedAt}, ciphertext = {ciphertext},
            recipient_signature = {recipientSignature}
        WHERE id = {id}::uuid
      """)
        .on(
          "id" -> requestId.toString,
          "state" -> stateStr(state),
          "respondedAt" -> respondedAt,
          "ciphertext" -> ciphertext.orNull,
          "recipientSignature" -> recipientSignature.toBytes
        )
        .executeUpdate()
    }

  override def deleteShareRequestById(id: UUID): Unit =
    db.withConnection { implicit conn =>
      SQL("DELETE FROM share_requests WHERE id = {id}::uuid")
        .on("id" -> id.toString)
        .executeUpdate()
    }

  override def deleteShareRequests(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Unit =
    db.withConnection { implicit conn =>
      (senderKey, secretId) match
        case (None, None) =>
          SQL("DELETE FROM share_requests WHERE recipient_key = {rk}")
            .on("rk" -> recipientKey.toBytes)
            .executeUpdate()
        case (Some(sk), None) =>
          SQL("DELETE FROM share_requests WHERE recipient_key = {rk} AND sender_key = {sk}")
            .on("rk" -> recipientKey.toBytes, "sk" -> sk.toBytes)
            .executeUpdate()
        case (None, Some(sid)) =>
          SQL("DELETE FROM share_requests WHERE recipient_key = {rk} AND secret_id = {sid}::uuid")
            .on("rk" -> recipientKey.toBytes, "sid" -> sid.value.toString)
            .executeUpdate()
        case (Some(sk), Some(sid)) =>
          SQL("""
            DELETE FROM share_requests
            WHERE recipient_key = {rk} AND sender_key = {sk} AND secret_id = {sid}::uuid
          """)
            .on("rk" -> recipientKey.toBytes, "sk" -> sk.toBytes, "sid" -> sid.value.toString)
            .executeUpdate()
    }

  override def withdrawDeposits(
      recipientKey: PublicKey,
      senderKey: Option[PublicKey],
      secretId: Option[SecretId]
  ): Unit =
    db.withConnection { implicit conn =>
      (senderKey, secretId) match
        case (None, None) =>
          SQL("""
            UPDATE share_requests SET state = 'withdrawn'
            WHERE recipient_key = {rk} AND transaction_type = 'deposit' AND state = 'approved'
          """)
            .on("rk" -> recipientKey.toBytes)
            .executeUpdate()
        case (Some(sk), None) =>
          SQL("""
            UPDATE share_requests SET state = 'withdrawn'
            WHERE recipient_key = {rk} AND sender_key = {sk}
              AND transaction_type = 'deposit' AND state = 'approved'
          """)
            .on("rk" -> recipientKey.toBytes, "sk" -> sk.toBytes)
            .executeUpdate()
        case (None, Some(sid)) =>
          SQL("""
            UPDATE share_requests SET state = 'withdrawn'
            WHERE recipient_key = {rk} AND secret_id = {sid}::uuid
              AND transaction_type = 'deposit' AND state = 'approved'
          """)
            .on("rk" -> recipientKey.toBytes, "sid" -> sid.value.toString)
            .executeUpdate()
        case (Some(sk), Some(sid)) =>
          SQL("""
            UPDATE share_requests SET state = 'withdrawn'
            WHERE recipient_key = {rk} AND sender_key = {sk} AND secret_id = {sid}::uuid
              AND transaction_type = 'deposit' AND state = 'approved'
          """)
            .on("rk" -> recipientKey.toBytes, "sk" -> sk.toBytes, "sid" -> sid.value.toString)
            .executeUpdate()
    }
