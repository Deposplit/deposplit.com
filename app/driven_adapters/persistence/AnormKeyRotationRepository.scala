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

  private def parseCipherSuite(s: String): CipherSuite =
    CipherSuite.fromWire(s).getOrElse(sys.error(s"corrupt cipher suite in DB: $s"))

  private val rotationParser: RowParser[KeyRotation] =
    get[UUID]("id") ~
      get[Array[Byte]]("old_verify_key") ~
      get[Array[Byte]]("recipient_key") ~
      get[Array[Byte]]("new_verify_key") ~
      get[Array[Byte]]("new_enc_key") ~
      get[String]("new_cipher_suite") ~
      get[Array[Byte]]("signature") ~
      get[Instant]("created_at") map { case id ~ oldVerify ~ rk ~ newVerify ~ newEnc ~ suite ~ sig ~ createdAt =>
        KeyRotation(
          id = id,
          oldVerifyKey = parseKey(oldVerify),
          recipientKey = parseKey(rk),
          newVerifyKey = parseKey(newVerify),
          newEncKey = parseX25519Key(newEnc),
          newCipherSuite = parseCipherSuite(suite),
          signature = parseSignature(sig),
          createdAt = createdAt
        )
      }

  override def saveRotation(rotation: KeyRotation): Unit =
    db.withConnection { implicit conn =>
      SQL("""
        INSERT INTO key_rotations
          (id, old_verify_key, recipient_key, new_verify_key, new_enc_key, new_cipher_suite, signature, created_at)
        VALUES
          ({id}::uuid, {oldVerify}, {rk}, {newVerify}, {newEnc}, {suite}, {sig}, {createdAt})
      """)
        .on(
          "id" -> rotation.id.toString,
          "oldVerify" -> rotation.oldVerifyKey.toBytes,
          "rk" -> rotation.recipientKey.toBytes,
          "newVerify" -> rotation.newVerifyKey.toBytes,
          "newEnc" -> rotation.newEncKey.toBytes,
          "suite" -> rotation.newCipherSuite.wireValue,
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
