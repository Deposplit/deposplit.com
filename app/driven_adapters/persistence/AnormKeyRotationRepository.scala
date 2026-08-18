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
import driven_ports.persistence.KeyRotationRepository
import jakarta.inject.*
import play.api.db.Database
import value_objects.*

import java.time.Instant
import java.util.UUID

@Singleton
class AnormKeyRotationRepository @Inject() (db: Database) extends KeyRotationRepository:

  // Instant conversion — H2 returns TIMESTAMP WITH TIME ZONE as OffsetDateTime. Duplicated from
  // AnormShareRepository rather than shared: this Column instance is a small adapter-local
  // concern, not domain logic worth a shared abstraction across two otherwise-unrelated tables.
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

  private def parseX25519Key(bytes: Array[Byte]): X25519Key =
    X25519Key.fromBytes(bytes).getOrElse(sys.error(s"corrupt X25519 key in DB (${bytes.length} bytes)"))

  private def parseSignature(bytes: Array[Byte]): Signature =
    Signature.fromBytes(bytes).getOrElse(sys.error(s"corrupt signature in DB (${bytes.length} bytes)"))

  private val rotationParser: RowParser[KeyRotation] =
    get[UUID]("id") ~
      get[Array[Byte]]("old_ed25519_key") ~
      get[Array[Byte]]("recipient_key") ~
      get[Array[Byte]]("new_ed25519_key") ~
      get[Array[Byte]]("new_x25519_key") ~
      get[Array[Byte]]("signature") ~
      get[Instant]("created_at") map {
        case id ~ oldEd ~ rk ~ newEd ~ newX ~ sig ~ createdAt =>
          KeyRotation(
            id = id,
            oldEd25519Key = parseKey(oldEd),
            recipientKey = parseKey(rk),
            newEd25519Key = parseKey(newEd),
            newX25519Key = parseX25519Key(newX),
            signature = parseSignature(sig),
            createdAt = createdAt
          )
      }

  override def saveRotation(rotation: KeyRotation): Unit =
    db.withConnection { implicit conn =>
      SQL("""
        INSERT INTO key_rotations
          (id, old_ed25519_key, recipient_key, new_ed25519_key, new_x25519_key, signature, created_at)
        VALUES
          ({id}::uuid, {oldEd}, {rk}, {newEd}, {newX}, {sig}, {createdAt})
      """)
        .on(
          "id" -> rotation.id.toString,
          "oldEd" -> rotation.oldEd25519Key.toBytes,
          "rk" -> rotation.recipientKey.toBytes,
          "newEd" -> rotation.newEd25519Key.toBytes,
          "newX" -> rotation.newX25519Key.toBytes,
          "sig" -> rotation.signature.toBytes,
          "createdAt" -> rotation.createdAt
        )
        .executeUpdate()
    }

  override def getRotationById(id: UUID): Option[KeyRotation] =
    db.withConnection { implicit conn =>
      SQL("SELECT * FROM key_rotations WHERE id = {id}::uuid")
        .on("id" -> id.toString)
        .as(rotationParser.singleOpt)
    }

  override def getRotationsForRecipient(recipientKey: PublicKey): Seq[KeyRotation] =
    db.withConnection { implicit conn =>
      SQL("SELECT * FROM key_rotations WHERE recipient_key = {rk}")
        .on("rk" -> recipientKey.toBytes)
        .as(rotationParser.*)
    }

  override def deleteRotationById(id: UUID): Unit =
    db.withConnection { implicit conn =>
      SQL("DELETE FROM key_rotations WHERE id = {id}::uuid")
        .on("id" -> id.toString)
        .executeUpdate()
    }
