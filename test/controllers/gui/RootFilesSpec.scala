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

import java.time.Duration
import java.time.Instant

/** Files that crawlers, scanners and link-preview fetchers request from a fixed URL, whatever the page itself links to.
  * Each must be routed ahead of the Markdown catch-all, which would otherwise answer with a 400 because it rejects any
  * dot.
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

  "GET /robots.txt" should {

    // A 4xx is not harmless here: RFC 9309 reads it as "no rules at all", so the catch-all's 400 would silently stand in
    // for whatever the file says.
    "serve the file as plain text rather than fall through to the Markdown catch-all" in {
      val robots = route(app, FakeRequest(GET, "/robots.txt")).get

      status(robots) mustBe OK
      contentType(robots) mustBe Some("text/plain")
      contentAsString(robots)(using defaultAwaitTimeout, app.materializer) must include("User-agent: *")
    }
  }

  "GET /.well-known/security.txt" should {

    // A def, not a val: the body is a stream, readable once, so each test needs a response of its own.
    def securityTxt = route(app, FakeRequest(GET, "/.well-known/security.txt")).get

    "serve the file as UTF-8 plain text, which RFC 9116 requires" in {
      status(securityTxt) mustBe OK
      contentType(securityTxt) mustBe Some("text/plain")
      charset(securityTxt) mustBe Some("utf-8")
      contentAsString(securityTxt)(using defaultAwaitTimeout, app.materializer) must include("Contact: https://")
    }

    // RFC 9116 treats a file past its Expires as stale, and recommends that date be less than a year out. Failing a
    // month early turns the yearly renewal into a build that asks for it while the published file is still valid.
    "expire more than a month and less than a year from now" in {
      val expires =
        contentAsString(securityTxt)(using defaultAwaitTimeout, app.materializer).linesIterator.collectFirst {
          case s"Expires: $timestamp" => Instant.parse(timestamp.trim)
        }.get
      val now = Instant.now()

      expires.isAfter(now.plus(Duration.ofDays(30))) mustBe true
      expires.isBefore(now.plus(Duration.ofDays(365))) mustBe true
    }
  }
}
