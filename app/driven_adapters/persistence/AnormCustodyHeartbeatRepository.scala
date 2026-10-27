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
import driven_ports.persistence.CustodyHeartbeatRepository
import jakarta.inject.*
import play.api.db.Database
import value_objects.*

import java.time.Instant
import java.util.UUID

@Singleton
class AnormCustodyHeartbeatRepository @Inject() (db: Database) extends CustodyHeartbeatRepository:

  // Instant conversion — H2 returns TIMESTAMP WITH TIME ZONE as OffsetDateTime. Duplicated from
  // AnormKeyRotationRepository rather than shared: a small adapter-local concern, not domain
  // logic worth a shared abstraction across otherwise-unrelated tables.
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

  private def parseSecretIds(s: String): Seq[UUID] =
    if s.isEmpty then Seq.empty else s.split(",").toSeq.map(UUID.fromString)

  private val heartbeatParser: RowParser[CustodyHeartbeat] =
    get[UUID]("id") ~
      get[Array[Byte]]("holder_key") ~
      get[Array[Byte]]("owner_key") ~
      get[String]("secret_ids") ~
      get[Boolean]("opted_out") ~
      get[Instant]("emitted_at") ~
      get[Array[Byte]]("signature") ~
      get[Instant]("created_at") map {
        case id ~ holderKey ~ ownerKey ~ secretIds ~ optedOut ~ emittedAt ~ sig ~ createdAt =>
          CustodyHeartbeat(
            id = id,
            holderKey = parseKey(holderKey),
            ownerKey = parseKey(ownerKey),
            secretIds = parseSecretIds(secretIds),
            optedOut = optedOut,
            emittedAt = emittedAt,
            signature = parseSignature(sig),
            createdAt = createdAt
          )
      }

  // Update-else-insert rather than `ON CONFLICT ... DO UPDATE`: H2's PostgreSQL compatibility mode
  // does not support Postgres upsert syntax, the same category of H2/Postgres gap documented in
  // conf/evolutions/default/1.sql for partial indexes — handled at the application layer instead
  // of relying on a DB-specific feature. Updating by the pair, rather than by an id selected
  // first, means an owner deleting the row in between simply leaves nothing to update, and the
  // push inserts instead. Two concurrent *first* pushes from the same holder to the same owner
  // (rare — the whole point of coalescing is one push per owner per interval) can still both
  // insert, and the unique constraint fails the second: that push errors, and the holder retries
  // it on its next poll, because it only counts a push as sent once the relay has accepted it.
  //
  // The UPDATE replaces the id as well, so an owner who read the old row and deletes it by that id cannot delete this
  // newer one, which she has not read yet. It also replaces created_at, which is what expiry keys on, so a heartbeat
  // expires only once its holder has stopped pushing.
  override def upsertHeartbeat(heartbeat: CustodyHeartbeat): CustodyHeartbeat =
    db.withTransaction { implicit conn =>
      val updated =
        SQL("""
          UPDATE custody_heartbeats
          SET id = {newId}::uuid, secret_ids = {secretIds}, opted_out = {optedOut}, emitted_at = {emittedAt},
              signature = {signature}, created_at = {createdAt}
          WHERE holder_key = {holderKey} AND owner_key = {ownerKey}
        """)
          .on(
            "holderKey" -> heartbeat.holderKey.toBytes,
            "ownerKey" -> heartbeat.ownerKey.toBytes,
            "newId" -> heartbeat.id.toString,
            "secretIds" -> heartbeat.secretIds.map(_.toString).sorted.mkString(","),
            "optedOut" -> heartbeat.optedOut,
            "emittedAt" -> heartbeat.emittedAt,
            "signature" -> heartbeat.signature.toBytes,
            "createdAt" -> heartbeat.createdAt
          )
          .executeUpdate()

      if updated == 0 then
        SQL("""
          INSERT INTO custody_heartbeats
            (id, holder_key, owner_key, secret_ids, opted_out, emitted_at, signature, created_at)
          VALUES
            ({id}::uuid, {holderKey}, {ownerKey}, {secretIds}, {optedOut}, {emittedAt}, {signature}, {createdAt})
        """)
          .on(
            "id" -> heartbeat.id.toString,
            "holderKey" -> heartbeat.holderKey.toBytes,
            "ownerKey" -> heartbeat.ownerKey.toBytes,
            "secretIds" -> heartbeat.secretIds.map(_.toString).sorted.mkString(","),
            "optedOut" -> heartbeat.optedOut,
            "emittedAt" -> heartbeat.emittedAt,
            "signature" -> heartbeat.signature.toBytes,
            "createdAt" -> heartbeat.createdAt
          )
          .executeUpdate()

      SQL("SELECT * FROM custody_heartbeats WHERE holder_key = {holderKey} AND owner_key = {ownerKey}")
        .on("holderKey" -> heartbeat.holderKey.toBytes, "ownerKey" -> heartbeat.ownerKey.toBytes)
        .as(heartbeatParser.single)
    }

  override def getHeartbeatsForOwner(ownerKey: PublicKey): Seq[CustodyHeartbeat] =
    db.withConnection { implicit conn =>
      SQL("SELECT * FROM custody_heartbeats WHERE owner_key = {ownerKey}")
        .on("ownerKey" -> ownerKey.toBytes)
        .as(heartbeatParser.*)
    }

  override def getHeartbeatById(id: UUID): Option[CustodyHeartbeat] =
    db.withConnection { implicit conn =>
      SQL("SELECT * FROM custody_heartbeats WHERE id = {id}::uuid")
        .on("id" -> id.toString)
        .as(heartbeatParser.singleOpt)
    }

  override def deleteHeartbeatById(id: UUID): Unit =
    db.withConnection { implicit conn =>
      SQL("DELETE FROM custody_heartbeats WHERE id = {id}::uuid")
        .on("id" -> id.toString)
        .executeUpdate()
    }

  override def deleteHeartbeatsReceivedBefore(cutoff: Instant): Int =
    db.withConnection { implicit conn =>
      SQL("DELETE FROM custody_heartbeats WHERE created_at < {cutoff}")
        .on("cutoff" -> cutoff)
        .executeUpdate()
    }
