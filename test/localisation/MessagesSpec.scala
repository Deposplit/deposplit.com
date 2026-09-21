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

package localisation

import org.scalatestplus.play.*
import play.api.i18n.Messages

import java.io.File
import java.text.MessageFormat
import java.util.Locale

/** Play compiles every message it renders into a `MessageFormat`, so a malformed pattern is not a wrong word — it is a
  * 500 on whichever screen shows it, in whichever locale has the typo, and only for the argument that reaches it. One
  * German plural shipped with `1#…|1#…` where it meant `1#…|1<…`, which `ChoiceFormat` rejects as intervals out of
  * order, and nothing caught it until a phony phone tried to warn about a changed key.
  *
  * Rendering every screen in every locale would catch the same thing; compiling every pattern catches it in a second
  * and without a browser.
  */
class MessagesSpec extends PlaySpec {

  private def load(file: String): Map[String, String] =
    val source = Messages.UrlMessageSource(File(file).toURI.toURL)
    Messages.parse(source, file).fold(failure => fail(s"$file does not parse: ${failure.getMessage}"), identity)

  private val locales = Map("conf/messages" -> Locale.ENGLISH, "conf/messages.de" -> Locale.GERMAN)

  "Every message" should {

    "compile as the pattern Play will render it through" in {
      locales.foreach { (file, locale) =>
        load(file).foreach { (key, pattern) =>
          withClue(s"$file: $key = $pattern: ") {
            noException must be thrownBy MessageFormat(pattern, locale)
          }
        }
      }
    }

    // The other direction is deliberately not asserted: `conf/messages.de` also overrides keys Play itself defines,
    // such as `error.required`, which have no entry of ours to sit beside in English.
    "have a German counterpart" in {
      val german = load("conf/messages.de").keySet
      load("conf/messages").keys.foreach { key =>
        withClue(s"conf/messages.de is missing $key: ") { german must contain(key) }
      }
    }
  }
}
