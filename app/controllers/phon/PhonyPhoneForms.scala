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

case class PseudonymRecord(pseudonym: String)

object PseudonymRecord:
  def unapply(pr: PseudonymRecord): Option[(String)] = Some((pr.pseudonym))

val pseudonymForm = Form(
  mapping(
    "pseudonym" -> nonEmptyText
  )(PseudonymRecord.apply)(PseudonymRecord.unapply)
)

case class ContactRecord(pseudonym: String, signKey: String, transKey: String)

object ContactRecord:
  def unapply(cr: ContactRecord): Option[(String, String, String)] = Some((cr.pseudonym, cr.signKey, cr.transKey))

val contactForm = Form(
  mapping(
    "pseudonym" -> nonEmptyText,
    "signKey" -> nonEmptyText,
    "transKey" -> nonEmptyText
  )(ContactRecord.apply)(ContactRecord.unapply)
)

case class SecretSharingRecord(secret: String, label: String, contacts: Seq[String])

object SecretSharingRecord:
  def unapply(ssr: SecretSharingRecord): Option[(String, String, Seq[String])] = Some(
    (ssr.secret, ssr.label, ssr.contacts)
  )

val secretSharingForm = Form(
  mapping(
    "secret" -> nonEmptyText,
    "label" -> nonEmptyText,
    "contacts" -> seq(text)
  )(SecretSharingRecord.apply)(SecretSharingRecord.unapply)
)
