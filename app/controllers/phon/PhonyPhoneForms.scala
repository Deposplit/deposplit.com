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

case class PseudonymForm(pseudonym: String)

object PseudonymForm:
  def unapply(pf: PseudonymForm): Option[(String)] = Some((pf.pseudonym))

val pseudonymForm = Form(
  mapping(
    "pseudonym" -> nonEmptyText
  )(PseudonymForm.apply)(PseudonymForm.unapply)
)

case class ContactForm(pseudonym: String, signKey: String, transKey: String)

object ContactForm:
  def unapply(cf: ContactForm): Option[(String, String, String)] = Some((cf.pseudonym, cf.signKey, cf.transKey))

val contactForm = Form(
  mapping(
    "pseudonym" -> nonEmptyText,
    "signKey" -> nonEmptyText,
    "transKey" -> nonEmptyText
  )(ContactForm.apply)(ContactForm.unapply)
)
