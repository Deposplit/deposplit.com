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
      get[Array[Byte]]("signature") ~
      get[Instant]("created_at") map {
        case id ~ holderKey ~ ownerKey ~ secretIds ~ optedOut ~ sig ~ createdAt =>
          CustodyHeartbeat(
            id = id,
            holderKey = parseKey(holderKey),
            ownerKey = parseKey(ownerKey),
            secretIds = parseSecretIds(secretIds),
            optedOut = optedOut,
            signature = parseSignature(sig),
            createdAt = createdAt
          )
      }

  // Plain select-then-insert-or-update rather than `ON CONFLICT ... DO UPDATE`: H2's PostgreSQL
  // compatibility mode does not support Postgres upsert syntax, the same category of H2/Postgres
  // gap documented in conf/evolutions/default/1.sql for partial indexes — handled at the
  // application layer instead of relying on a DB-specific feature. A benign race between two
  // concurrent pushes from the same holder to the same owner (rare — the whole point of
  // coalescing is one push per sender per interval) can at worst leave either push's content
  // persisted, which is harmless: both are freshness proofs from the same holder.
  override def upsertHeartbeat(heartbeat: CustodyHeartbeat): CustodyHeartbeat =
    db.withConnection { implicit conn =>
      val existingId = SQL("SELECT id FROM custody_heartbeats WHERE holder_key = {holderKey} AND owner_key = {ownerKey}")
        .on("holderKey" -> heartbeat.holderKey.toBytes, "ownerKey" -> heartbeat.ownerKey.toBytes)
        .as(get[UUID]("id").singleOpt)

      existingId match
        case Some(id) =>
          SQL("""
            UPDATE custody_heartbeats
            SET secret_ids = {secretIds}, opted_out = {optedOut}, signature = {signature}, created_at = {createdAt}
            WHERE id = {id}::uuid
          """)
            .on(
              "id" -> id.toString,
              "secretIds" -> heartbeat.secretIds.map(_.toString).sorted.mkString(","),
              "optedOut" -> heartbeat.optedOut,
              "signature" -> heartbeat.signature.toBytes,
              "createdAt" -> heartbeat.createdAt
            )
            .executeUpdate()
        case None =>
          SQL("""
            INSERT INTO custody_heartbeats
              (id, holder_key, owner_key, secret_ids, opted_out, signature, created_at)
            VALUES
              ({id}::uuid, {holderKey}, {ownerKey}, {secretIds}, {optedOut}, {signature}, {createdAt})
          """)
            .on(
              "id" -> heartbeat.id.toString,
              "holderKey" -> heartbeat.holderKey.toBytes,
              "ownerKey" -> heartbeat.ownerKey.toBytes,
              "secretIds" -> heartbeat.secretIds.map(_.toString).sorted.mkString(","),
              "optedOut" -> heartbeat.optedOut,
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
