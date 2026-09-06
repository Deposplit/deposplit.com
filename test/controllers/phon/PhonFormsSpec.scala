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

import controllers.phon.PhonForms.DepositPayloadError
import org.scalatestplus.play.*
import play.api.i18n.Lang
import play.api.i18n.Messages
import play.api.i18n.MessagesImpl
import play.api.i18n.DefaultMessagesApi
import value_objects.svo.MimeType
import value_objects.svo.ReconstructionIntegrity
import value_objects.svo.ReconstructionResult

import java.nio.charset.StandardCharsets

/** What a deposit is actually splitting, and what it declares that to be.
  *
  * The rules worth pinning are the ones a form cannot express: the type is read off the bytes rather than off a file
  * name or a browser's claim, anything that is not PNG or JPEG is refused outright, and text plus a file together is
  * refused rather than silently resolved.
  */
class PhonFormsSpec extends PlaySpec {

  private val png = Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte) ++ Array[Byte](1, 2, 3)
  private val jpeg = Array(0xff, 0xd8, 0xff).map(_.toByte) ++ Array[Byte](4, 5, 6)

  private given messages: Messages =
    MessagesImpl(Lang("en"), DefaultMessagesApi(Map("en" -> Map("phon.reconstruct.opaque" -> "{0}, {1} bytes"))))

  "A typed secret" should {

    // Compared as a list throughout this file: an Array compares by reference, so `mustBe` on one
    // passes or fails for reasons that have nothing to do with the bytes.
    "be split as text" in {
      PhonForms.depositPayload(Some("hunter2"), None).map((bytes, mimeType) => bytes.toList -> mimeType) mustBe
        Right("hunter2".getBytes(StandardCharsets.UTF_8).toList -> MimeType.Default)
    }

    // The label is stripped; the secret is not. What goes in has to come back byte for byte.
    "keep its own leading and trailing whitespace" in {
      val Right((bytes, _)) = PhonForms.depositPayload(Some("  spaced  "), None): @unchecked
      String(bytes, StandardCharsets.UTF_8) mustBe "  spaced  "
    }

    "count as absent when it is only whitespace" in {
      PhonForms.depositPayload(Some("   "), None) mustBe Left(DepositPayloadError.Missing)
    }
  }

  "An uploaded file" should {

    "be recognised as PNG by its leading bytes" in {
      PhonForms.depositPayload(None, Some(png)).map(_._2) mustBe Right(MimeType.Png)
    }

    "be recognised as JPEG by its leading bytes" in {
      PhonForms.depositPayload(None, Some(jpeg)).map(_._2) mustBe Right(MimeType.Jpeg)
    }

    "be split byte for byte, with nothing added or trimmed" in {
      PhonForms.depositPayload(None, Some(png)).map(_._1.toList) mustBe Right(png.toList)
    }

    // Never application/octet-stream: each extra format is more decoder surface reached by
    // attacker-chosen bytes, and the mobile pickers accept exactly these two.
    "be refused when it is neither PNG nor JPEG" in {
      PhonForms.depositPayload(None, Some("GIF89a".getBytes(StandardCharsets.UTF_8))) mustBe
        Left(DepositPayloadError.UnsupportedType)
    }

    "be refused when it only claims to be an image in its name" in {
      PhonForms.depositPayload(None, Some("this is not a png".getBytes(StandardCharsets.UTF_8))) mustBe
        Left(DepositPayloadError.UnsupportedType)
    }
  }

  "A deposit with nothing to split" should {
    "be refused" in {
      PhonForms.depositPayload(None, None) mustBe Left(DepositPayloadError.Missing)
      PhonForms.depositPayload(Some(""), None) mustBe Left(DepositPayloadError.Missing)
    }
  }

  "A deposit with both text and a file" should {
    // The mobile forms make the pair impossible by swapping one control for the other. A plain HTML
    // form cannot, so it says so rather than dropping one of them.
    "be refused rather than resolved in the file's favour" in {
      PhonForms.depositPayload(Some("hunter2"), Some(png)) mustBe Left(DepositPayloadError.Ambiguous)
    }
  }

  "A reconstructed secret" should {

    // The other half of being able to split an image: phon renders it, rather than reporting a byte
    // count the way it does for a type it cannot show.
    "render an image inline when that is what was split" in {
      val result = ReconstructionResult(png, ReconstructionIntegrity.Confirmed, MimeType.Png)
      val html = views.html.Phon.reconstruction(result).body
      html must include("data:image/png;base64,")
      html must include(java.util.Base64.getEncoder.encodeToString(png))
    }

    "render text as text" in {
      val bytes = "hunter2".getBytes(StandardCharsets.UTF_8)
      val html = views.html.Phon
        .reconstruction(ReconstructionResult(bytes, ReconstructionIntegrity.NoMargin, MimeType.Default))
        .body
      html must include("hunter2")
      html must not include "data:"
    }

    // Nothing else is offered as a download or guessed at: a type phon cannot show, it names.
    "say so plainly for a type it cannot show" in {
      val result =
        ReconstructionResult(Array[Byte](1, 2, 3), ReconstructionIntegrity.Confirmed, MimeType("application/zip"))
      val html = views.html.Phon.reconstruction(result).body
      html must include("application/zip")
      html must not include "data:"
    }
  }

  "Every payload refusal" should {
    "name a message key that exists in both locales" in {
      val keys = DepositPayloadError.values.map(_.messageKey).toSet
      for locale <- List("conf/messages", "conf/messages.de") do
        val defined = scala.io.Source
          .fromFile(locale, "UTF-8")
          .getLines()
          .collect { case line if line.contains("=") => line.split("=")(0).strip() }
          .toSet
        withClue(s"$locale: ") { (keys -- defined) mustBe empty }
    }
  }
}
