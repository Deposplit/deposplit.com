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

package persistence

import anorm.*
import anorm.SqlParser.*
import driven_ports.persistence.ShareRepository
import play.api.db.Database
import value_objects.*
import java.time.Instant
import java.util.UUID
import javax.inject.*

@Singleton
class AnormShareRepository @Inject() (db: Database) extends ShareRepository:

  // ---------------------------------------------------------------------------
  // Row parsers
  // Note: UUID parameters are passed as strings; both H2 and PostgreSQL auto-cast.
  // ---------------------------------------------------------------------------

  // H2 in PostgreSQL mode returns TIMESTAMP WITH TIME ZONE as java.time.OffsetDateTime.
  // Anorm's built-in Column[Instant] only handles java.sql.Timestamp and java.util.Date.
  private given Column[Instant] = Column.nonNull { (value, meta) =>
    value match
      case ts:  java.sql.Timestamp       => Right(ts.toInstant)
      case odt: java.time.OffsetDateTime => Right(odt.toInstant)
      case d:   java.util.Date           => Right(d.toInstant)
      case _ =>
        Left(TypeDoesNotMatch(s"Cannot convert $value: ${value.getClass.getName} to Instant for column ${meta.column}"))
  }

  private def parseKey(bytes: Array[Byte]): PublicKey =
    PublicKey.fromBytes(bytes).getOrElse(sys.error(s"corrupt public key in DB (${bytes.length} bytes)"))

  private val shareParser: RowParser[Share] =
    get[UUID]("id") ~
      get[UUID]("secret_id") ~
      get[Array[Byte]]("sender_key") ~
      get[Array[Byte]]("recipient_key") ~
      get[String]("label") ~
      get[java.time.Instant]("created_at") ~
      get[Option[Array[Byte]]]("ciphertext") ~
      get[Option[java.time.Instant]]("picked_up_at") map { case id ~ sid ~ sk ~ rk ~ lbl ~ ts ~ ct ~ pu =>
        Share(
          id           = id,
          secretId     = SecretId(sid),
          senderKey    = parseKey(sk),
          recipientKey = parseKey(rk),
          label        = Label(lbl),
          createdAt    = ts,
          ciphertext   = ct,
          pickedUpAt   = pu
        )
      }

  private val shareMetaParser: RowParser[ShareMetadata] =
    get[UUID]("id") ~
      get[UUID]("secret_id") ~
      get[Array[Byte]]("sender_key") ~
      get[Array[Byte]]("recipient_key") ~
      get[String]("label") ~
      get[java.time.Instant]("created_at") ~
      get[Option[java.time.Instant]]("picked_up_at") map { case id ~ sid ~ sk ~ rk ~ lbl ~ ts ~ pu =>
        ShareMetadata(
          id           = id,
          secretId     = SecretId(sid),
          senderKey    = parseKey(sk),
          recipientKey = parseKey(rk),
          label        = Label(lbl),
          createdAt    = ts,
          pickedUpAt   = pu
        )
      }

  // Parses a share_requests row JOINed with its parent share.
  // Column aliases: sr_id, sr_ciphertext, s_id, s_secret_id, s_sender_key, s_recipient_key, s_label, s_created_at, s_picked_up_at
  private val shareRequestParser: RowParser[ShareRequest] =
    get[UUID]("sr_id") ~
      get[String]("request_type") ~
      get[String]("state") ~
      get[Option[Array[Byte]]]("sr_ciphertext") ~
      get[java.time.Instant]("requested_at") ~
      get[Option[java.time.Instant]]("responded_at") ~
      get[UUID]("s_id") ~
      get[UUID]("s_secret_id") ~
      get[Array[Byte]]("s_sender_key") ~
      get[Array[Byte]]("s_recipient_key") ~
      get[String]("s_label") ~
      get[java.time.Instant]("s_created_at") ~
      get[Option[java.time.Instant]]("s_picked_up_at") map {
        case srId ~ rt ~ st ~ srCt ~ reqAt ~ resAt ~ sId ~ sSid ~ sSk ~ sRk ~ sLbl ~ sTs ~ sPu =>
          ShareRequest(
            id          = srId,
            share       = ShareMetadata(
              id           = sId,
              secretId     = SecretId(sSid),
              senderKey    = parseKey(sSk),
              recipientKey = parseKey(sRk),
              label        = Label(sLbl),
              createdAt    = sTs,
              pickedUpAt   = sPu
            ),
            requestType = rt match
              case "retrieve" => ShareRequestType.Retrieve
              case "delete"   => ShareRequestType.Delete
              case other      => sys.error(s"unknown request_type: $other"),
            state       = st match
              case "pending"  => ShareRequestState.Pending
              case "approved" => ShareRequestState.Approved
              case "denied"   => ShareRequestState.Denied
              case other      => sys.error(s"unknown state: $other"),
            createdAt   = reqAt,
            respondedAt = resAt,
            ciphertext  = srCt
          )
      }

  // SELECT fragment producing the column aliases expected by shareRequestParser.
  private val requestSelectCols =
    """sr.id           AS sr_id,
      |sr.request_type,
      |sr.state,
      |sr.ciphertext   AS sr_ciphertext,
      |sr.requested_at,
      |sr.responded_at,
      |s.id            AS s_id,
      |s.secret_id     AS s_secret_id,
      |s.sender_key    AS s_sender_key,
      |s.recipient_key AS s_recipient_key,
      |s.label         AS s_label,
      |s.created_at    AS s_created_at,
      |s.picked_up_at  AS s_picked_up_at""".stripMargin

  private val requestJoin = "FROM share_requests sr JOIN shares s ON sr.share_id = s.id"

  // ---------------------------------------------------------------------------
  // Shares
  // ---------------------------------------------------------------------------

  override def saveShare(share: Share): Unit =
    db.withConnection { implicit conn =>
      SQL("""
        INSERT INTO shares (id, secret_id, label, sender_key, recipient_key, ciphertext, created_at)
        VALUES ({id}, {secretId}::uuid, {label}, {senderKey}, {recipientKey}, {ciphertext}, {createdAt})
      """).on(
        "id"           -> share.id.toString,
        "secretId"     -> share.secretId.value.toString,
        "label"        -> share.label.value,
        "senderKey"    -> share.senderKey.toBytes,
        "recipientKey" -> share.recipientKey.toBytes,
        "ciphertext"   -> share.ciphertext.orNull,
        "createdAt"    -> share.createdAt
      ).executeUpdate()
    }

  override def getShareById(id: UUID): Option[Share] =
    db.withConnection { implicit conn =>
      SQL("""
        SELECT id, secret_id, sender_key, recipient_key, label, created_at, ciphertext, picked_up_at
        FROM shares WHERE id = {id}::uuid
      """).on("id" -> id.toString)
        .as(shareParser.singleOpt)
    }

  override def getShare(secretId: SecretId, senderKey: PublicKey, recipientKey: PublicKey): Option[Share] =
    db.withConnection { implicit conn =>
      SQL("""
        SELECT id, secret_id, sender_key, recipient_key, label, created_at, ciphertext, picked_up_at
        FROM shares
        WHERE secret_id = {sid}::uuid AND sender_key = {sk} AND recipient_key = {rk}
      """).on(
        "sid" -> secretId.value.toString,
        "sk"  -> senderKey.toBytes,
        "rk"  -> recipientKey.toBytes
      ).as(shareParser.singleOpt)
    }

  override def getSharesAsSender(senderKey: PublicKey, counterpartyKey: Option[PublicKey]): Seq[ShareMetadata] =
    db.withConnection { implicit conn =>
      counterpartyKey match
        case None =>
          SQL("SELECT id, secret_id, sender_key, recipient_key, label, created_at, picked_up_at FROM shares WHERE sender_key = {sk}")
            .on("sk" -> senderKey.toBytes)
            .as(shareMetaParser.*)
        case Some(ck) =>
          SQL("""
            SELECT id, secret_id, sender_key, recipient_key, label, created_at, picked_up_at
            FROM shares WHERE sender_key = {sk} AND recipient_key = {ck}
          """).on("sk" -> senderKey.toBytes, "ck" -> ck.toBytes)
            .as(shareMetaParser.*)
    }

  override def getSharesAsRecipient(recipientKey: PublicKey, counterpartyKey: Option[PublicKey]): Seq[ShareMetadata] =
    db.withConnection { implicit conn =>
      counterpartyKey match
        case None =>
          SQL("""
            SELECT id, secret_id, sender_key, recipient_key, label, created_at, picked_up_at
            FROM shares WHERE recipient_key = {rk} AND picked_up_at IS NULL
          """).on("rk" -> recipientKey.toBytes)
            .as(shareMetaParser.*)
        case Some(ck) =>
          SQL("""
            SELECT id, secret_id, sender_key, recipient_key, label, created_at, picked_up_at
            FROM shares WHERE recipient_key = {rk} AND sender_key = {ck} AND picked_up_at IS NULL
          """).on("rk" -> recipientKey.toBytes, "ck" -> ck.toBytes)
            .as(shareMetaParser.*)
    }

  override def pickUpShare(shareId: UUID): Unit =
    db.withConnection { implicit conn =>
      SQL("""
        UPDATE shares SET ciphertext = NULL, picked_up_at = {now} WHERE id = {id}::uuid
      """).on("id" -> shareId.toString, "now" -> Instant.now()).executeUpdate()
    }

  override def deleteShareByPK(shareId: UUID): Unit =
    db.withConnection { implicit conn =>
      SQL("DELETE FROM shares WHERE id = {id}::uuid").on("id" -> shareId.toString).executeUpdate()
    }

  override def deleteShares(recipientKey: PublicKey, senderKey: Option[PublicKey], secretId: Option[SecretId]): Unit =
    db.withConnection { implicit conn =>
      (senderKey, secretId) match
        case (None, None) =>
          SQL("DELETE FROM shares WHERE recipient_key = {rk}")
            .on("rk" -> recipientKey.toBytes).executeUpdate()
        case (Some(sk), None) =>
          SQL("DELETE FROM shares WHERE recipient_key = {rk} AND sender_key = {sk}")
            .on("rk" -> recipientKey.toBytes, "sk" -> sk.toBytes).executeUpdate()
        case (None, Some(sid)) =>
          SQL("DELETE FROM shares WHERE recipient_key = {rk} AND secret_id = {sid}::uuid")
            .on("rk" -> recipientKey.toBytes, "sid" -> sid.value.toString).executeUpdate()
        case (Some(sk), Some(sid)) =>
          SQL("DELETE FROM shares WHERE recipient_key = {rk} AND sender_key = {sk} AND secret_id = {sid}::uuid")
            .on("rk" -> recipientKey.toBytes, "sk" -> sk.toBytes, "sid" -> sid.value.toString).executeUpdate()
    }

  // ---------------------------------------------------------------------------
  // Share requests
  // ---------------------------------------------------------------------------

  override def saveShareRequest(request: ShareRequest): Unit =
    db.withConnection { implicit conn =>
      SQL("""
        INSERT INTO share_requests (id, share_id, request_type, state, requested_at)
        VALUES ({id}::uuid, {shareId}::uuid, {requestType}, 'pending', {requestedAt})
      """).on(
        "id"          -> request.id.toString,
        "shareId"     -> request.share.id.toString,
        "requestType" -> (request.requestType match
          case ShareRequestType.Retrieve => "retrieve"
          case ShareRequestType.Delete   => "delete"),
        "requestedAt" -> request.createdAt
      ).executeUpdate()
    }

  override def getShareRequestById(requestId: UUID): Option[ShareRequest] =
    db.withConnection { implicit conn =>
      SQL(s"SELECT $requestSelectCols $requestJoin WHERE sr.id = {id}::uuid")
        .on("id" -> requestId.toString)
        .as(shareRequestParser.singleOpt)
    }

  override def getShareRequestsAsSender(senderKey: PublicKey, state: Option[ShareRequestState]): Seq[ShareRequest] =
    db.withConnection { implicit conn =>
      state match
        case None =>
          SQL(s"SELECT $requestSelectCols $requestJoin WHERE s.sender_key = {sk}")
            .on("sk" -> senderKey.toBytes).as(shareRequestParser.*)
        case Some(st) =>
          SQL(s"SELECT $requestSelectCols $requestJoin WHERE s.sender_key = {sk} AND sr.state = {state}")
            .on("sk" -> senderKey.toBytes, "state" -> stateStr(st)).as(shareRequestParser.*)
    }

  override def getShareRequestsAsRecipient(recipientKey: PublicKey, state: Option[ShareRequestState]): Seq[ShareRequest] =
    db.withConnection { implicit conn =>
      state match
        case None =>
          SQL(s"SELECT $requestSelectCols $requestJoin WHERE s.recipient_key = {rk}")
            .on("rk" -> recipientKey.toBytes).as(shareRequestParser.*)
        case Some(st) =>
          SQL(s"SELECT $requestSelectCols $requestJoin WHERE s.recipient_key = {rk} AND sr.state = {state}")
            .on("rk" -> recipientKey.toBytes, "state" -> stateStr(st)).as(shareRequestParser.*)
    }

  override def hasPendingRequest(shareId: UUID, requestType: ShareRequestType): Boolean =
    db.withConnection { implicit conn =>
      val count = SQL("""
        SELECT COUNT(*) FROM share_requests
        WHERE share_id = {shareId}::uuid AND request_type = {requestType} AND state = 'pending'
      """).on(
        "shareId"     -> shareId.toString,
        "requestType" -> (requestType match
          case ShareRequestType.Retrieve => "retrieve"
          case ShareRequestType.Delete   => "delete")
      ).as(scalar[Long].single)
      count > 0
    }

  override def updateShareRequest(requestId: UUID, state: ShareRequestState, respondedAt: Instant, ciphertext: Option[Array[Byte]]): Unit =
    db.withConnection { implicit conn =>
      SQL("""
        UPDATE share_requests SET state = {state}, responded_at = {respondedAt}, ciphertext = {ciphertext}
        WHERE id = {id}::uuid
      """).on(
        "id"          -> requestId.toString,
        "state"       -> stateStr(state),
        "respondedAt" -> respondedAt,
        "ciphertext"  -> ciphertext
      ).executeUpdate()
    }

  private def stateStr(state: ShareRequestState): String = state match
    case ShareRequestState.Pending  => "pending"
    case ShareRequestState.Approved => "approved"
    case ShareRequestState.Denied   => "denied"
