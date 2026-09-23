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

/** Files that crawlers and link-preview fetchers request from a fixed URL, whatever the page itself links to. Each must
  * be routed ahead of the Markdown catch-all, which would otherwise answer with a 400 because it rejects any dot.
  */
class RootFilesSpec extends PlaySpec with GuiceOneAppPerSuite {

  "GET /favicon.ico" should {

    "serve an icon rather than fall through to the Markdown catch-all" in {
      val icon = route(app, FakeRequest(GET, "/favicon.ico")).get

      status(icon) mustBe OK
      contentType(icon) mustBe Some("image/x-icon")
      // An asset's body is streamed rather than strict, so reading it needs the application's materializer.
      contentAsBytes(icon)(using defaultAwaitTimeout, app.materializer).take(4).toSeq mustBe Seq[Byte](0, 0, 1, 0)
    }
  }
}
