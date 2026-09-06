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

package controllers.phon

import play.api.data.*
import play.api.data.Forms.*
import value_objects.svo.MimeType
import value_objects.svo.VerificationLevel

import java.nio.charset.StandardCharsets

/** The forms behind phon's screens. Kept in one object rather than as loose top-level vals so a template can name where
  * a form came from.
  */
object PhonForms:

  final case class PseudonymRecord(pseudonym: String)

  val pseudonymForm: Form[PseudonymRecord] = Form(
    mapping("pseudonym" -> nonEmptyText)(PseudonymRecord.apply)(pr => Some(pr.pseudonym))
  )

  /** Manual key entry. `verificationLevel` is required, and the picker deliberately offers everything except
    * `VeryHigh`: that level means "we were in the same room", which typing a key out of a chat window is not.
    */
  final case class ManualContactRecord(
      pseudonym: String,
      verifyKey: String,
      encKey: String,
      verificationLevel: String,
      relayBaseUrl: Option[String],
      nickname: Option[String]
  )

  val manualContactForm: Form[ManualContactRecord] = Form(
    mapping(
      "pseudonym" -> nonEmptyText,
      "verifyKey" -> nonEmptyText,
      "encKey" -> nonEmptyText,
      "verificationLevel" -> nonEmptyText.verifying("phon.error.verificationLevel", levelNames.contains),
      "relayBaseUrl" -> optional(text),
      "nickname" -> optional(text)
    )(ManualContactRecord.apply)(r =>
      Some((r.pseudonym, r.verifyKey, r.encKey, r.verificationLevel, r.relayBaseUrl, r.nickname))
    )
  )

  /** phon has no camera, so the payload a phone would scan is pasted instead. Same `addFromQr` path, same
    * self-described cipher suite — only the transport differs, which is why the level defaults to `VeryHigh` here as it
    * does for a real scan.
    */
  final case class PayloadContactRecord(payload: String, verificationLevel: String, nickname: Option[String])

  val payloadContactForm: Form[PayloadContactRecord] = Form(
    mapping(
      "payload" -> nonEmptyText,
      "verificationLevel" -> nonEmptyText.verifying("phon.error.verificationLevel", levelNames.contains),
      "nickname" -> optional(text)
    )(PayloadContactRecord.apply)(r => Some((r.payload, r.verificationLevel, r.nickname)))
  )

  /** A relink re-presents keys for a contact that already exists, so it never mints a new id. The level is chosen fresh
    * every time — a key change proves continuity of key control, never personhood.
    */
  final case class RelinkRecord(
      payload: Option[String],
      verifyKey: Option[String],
      encKey: Option[String],
      verificationLevel: String
  )

  val relinkForm: Form[RelinkRecord] = Form(
    mapping(
      "payload" -> optional(text),
      "verifyKey" -> optional(text),
      "encKey" -> optional(text),
      "verificationLevel" -> nonEmptyText.verifying("phon.error.verificationLevel", levelNames.contains)
    )(RelinkRecord.apply)(r => Some((r.payload, r.verifyKey, r.encKey, r.verificationLevel)))
      .verifying(
        "phon.error.relinkEmpty",
        r =>
          r.payload.exists(_.trim.nonEmpty) || (r.verifyKey.exists(_.trim.nonEmpty) && r.encKey.exists(_.trim.nonEmpty))
      )
  )

  final case class RenameRecord(nickname: Option[String])

  val renameForm: Form[RenameRecord] = Form(
    mapping("nickname" -> optional(text))(RenameRecord.apply)(r => Some(r.nickname))
  )

  /** `secret` is optional because a deposit may instead arrive as an uploaded file; which of the two was given is
    * decided by [[depositPayload]], not by the mapping, since a `Form` cannot see a multipart file part.
    */
  final case class DepositRecord(label: String, secret: Option[String], k: Int, contacts: Seq[String])

  val depositForm: Form[DepositRecord] = Form(
    mapping(
      "label" -> nonEmptyText,
      "secret" -> optional(text),
      "k" -> number(min = 2),
      "contacts" -> seq(text).verifying("phon.error.blankContact", _.forall(!_.isBlank))
    )(DepositRecord.apply)(r => Some((r.label, r.secret, r.k, r.contacts)))
      .verifying("phon.error.thresholdAboveHolders", r => r.contacts.size >= r.k)
  )

  enum DepositPayloadError(val messageKey: String):
    case Missing extends DepositPayloadError("phon.error.secretMissing")
    case Ambiguous extends DepositPayloadError("phon.error.secretAmbiguous")
    case UnsupportedType extends DepositPayloadError("phon.error.unsupportedType")

  /** What is actually being split, and what the owner is declaring it to be.
    *
    * The type is read off the leading bytes, never off the file name or the browser's claimed content type - the same
    * rule `MimeType.sniffed` states, and the reason a deposit's declared type cannot disagree with its payload. Bytes
    * that are neither PNG nor JPEG are refused rather than sent as `application/octet-stream`: every extra format is
    * more decoder surface reached by attacker-chosen bytes, and the mobile pickers accept exactly these two.
    *
    * Text and a file together are refused rather than silently resolved in the file's favour. The mobile forms make the
    * pair impossible by swapping one control for the other; a plain HTML form cannot, and quietly dropping what
    * somebody typed is the kind of loss nobody notices.
    */
  def depositPayload(
      text: Option[String],
      upload: Option[Array[Byte]]
  ): Either[DepositPayloadError, (Array[Byte], MimeType)] =
    // Stripped to decide whether anything was typed, but kept whole to split: what goes in has to come back byte for
    // byte, and a secret is entitled to its own leading and trailing whitespace.
    val typed = text.filter(_.strip().nonEmpty)
    (typed, upload) match
      case (Some(_), Some(_))  => Left(DepositPayloadError.Ambiguous)
      case (None, None)        => Left(DepositPayloadError.Missing)
      case (Some(value), None) => Right(value.getBytes(StandardCharsets.UTF_8) -> MimeType.Default)
      case (None, Some(bytes)) =>
        MimeType.sniffed(bytes).toRight(DepositPayloadError.UnsupportedType).map(bytes -> _)

  final case class RelayRecord(relayBaseUrl: Option[String])

  val relayForm: Form[RelayRecord] = Form(
    mapping("relayBaseUrl" -> optional(text))(RelayRecord.apply)(r => Some(r.relayBaseUrl))
  )

  final case class ResponseRecord(approved: Boolean)

  val responseForm: Form[ResponseRecord] = Form(
    mapping("approved" -> boolean)(ResponseRecord.apply)(r => Some(r.approved))
  )

  final case class OpenRequestRecord(transactionType: String)

  val openRequestForm: Form[OpenRequestRecord] =
    Form(mapping("transactionType" -> nonEmptyText)(OpenRequestRecord.apply)(r => Some(r.transactionType)))

  private def levelNames: Set[String] = VerificationLevel.values.map(_.toString).toSet

  def levelFrom(name: String): VerificationLevel =
    VerificationLevel.values.find(_.toString == name).getOrElse(VerificationLevel.VeryLow)

  /** What a human may claim for themselves. `VeryHigh` is earned by being in the same room, so it is offered only on
    * the path that stands in for a scan — never on manual entry, exactly as `AddContactView` and `AddContactScreen` do
    * it.
    */
  val manualLevels: List[VerificationLevel] = VerificationLevel.values.toList.filterNot(_ == VerificationLevel.VeryHigh)
  val allLevels: List[VerificationLevel] = VerificationLevel.values.toList
