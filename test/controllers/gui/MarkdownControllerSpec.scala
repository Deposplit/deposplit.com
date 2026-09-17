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

package controllers.gui

import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import play.api.test.*
import play.api.test.Helpers.*

/** Landing page copy is looked up in the reader's language first and in the default folder second. There is no `en/`
  * folder, so English reaching the English copy is the fallback at work, and only a page missing from both is Not
  * Found.
  */
class MarkdownControllerSpec extends PlaySpec with GuiceOneAppPerSuite {

  "MarkdownController GET" should {

    "serve the copy from the reader's language folder" in {
      val page = route(app, FakeRequest(GET, "/problem").withHeaders(ACCEPT_LANGUAGE -> "de")).get

      status(page) mustBe OK
      contentAsString(page) must include("Das Problem")
    }

    "fall back to the default folder when the language has none" in {
      val page = route(app, FakeRequest(GET, "/problem").withHeaders(ACCEPT_LANGUAGE -> "en")).get

      status(page) mustBe OK
      contentAsString(page) must include("The Problem")
    }

    "answer Not Found for a page that exists in neither" in {
      val page = route(app, FakeRequest(GET, "/no-such-page")).get

      status(page) mustBe NOT_FOUND
    }
  }
}
