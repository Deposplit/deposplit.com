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

import org.scalatestplus.play.*
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.*
import play.api.test.CSRFTokenHelper.*
import play.api.test.Helpers.*

import java.io.File

/** phon had no controller or view tests at all, which is how three screens shipped reading `HANDLE REQUEST`, and how
  * `GET /contacts` came to answer a bare `<div>` with no document around it.
  *
  * The two properties worth pinning are exactly those: every screen is a whole page to a browser and a `#screen`
  * fragment to htmx, and every screen renders without the relay - a phony phone pointed at a relay that is down must
  * still show what it knows, which is Flow 5 in docs/testing.md.
  *
  * The relay is configured to a port nothing listens on precisely so that a screen which quietly started calling out
  * would hang or fail here rather than pass by talking to whatever happened to be running.
  */
class PhonScreensSpec extends PlaySpec {

  // Its own port, because the File* adapters name their files after one - so this suite gets its own phone rather
  // than borrowing whichever one a developer left registered.
  private val port = 19100
  private val stores = List(
    "identity",
    "contacts",
    "contactrelinks",
    "secrets",
    "shares",
    "sharemetadata",
    "keyconflicts",
    "retaineddeposits"
  ).map(name => File(s"./.devDBs/$name$port.ser"))
  private val relayFile = File(s"./.devDBs/relay$port.txt")

  private def freshPhone(): Application =
    File("./.devDBs").mkdirs()
    stores.foreach(_.delete())
    relayFile.delete()
    java.nio.file.Files.writeString(relayFile.toPath, "http://127.0.0.1:1")
    GuiceApplicationBuilder()
      .configure("play.http.router" -> "dev.Routes", "http.port" -> port)
      .build()

  private def withPhone(registered: Boolean)(check: Application => Unit): Unit =
    val app = freshPhone()
    try
      if registered then
        val result = route(
          app,
          FakeRequest(POST, "/phonyPhone/register").withFormUrlEncodedBody("pseudonym" -> "Alice").withCSRFToken
        ).get
        status(result) mustBe SEE_OTHER
      check(app)
    finally
      app.stop()
      stores.foreach(_.delete())
      relayFile.delete()

  private val screens = List(
    "/phonyPhone",
    "/phonyPhone/held",
    "/phonyPhone/requests",
    "/phonyPhone/contacts",
    "/phonyPhone/contacts/new",
    "/phonyPhone/deposit",
    "/phonyPhone/qr",
    "/phonyPhone/settings"
  )

  "Every phon screen" should {

    "answer a plain browser request with a whole document" in {
      withPhone(registered = true) { app =>
        for path <- screens do
          val result = route(app, FakeRequest(GET, path)).get
          withClue(s"$path: ") {
            status(result) mustBe OK
            contentType(result) mustBe Some("text/html")
            contentAsString(result) must include("<!doctype html>")
            contentAsString(result) must include("""<div id="screen"""")
          }
      }
    }

    "answer an htmx request with the screen fragment alone" in {
      withPhone(registered = true) { app =>
        for path <- screens do
          val result = route(app, FakeRequest(GET, path).withHeaders("HX-Request" -> "true")).get
          withClue(s"$path: ") {
            status(result) mustBe OK
            contentAsString(result) must include("""<div id="screen"""")
            contentAsString(result) must not include "<!doctype html>"
            contentAsString(result) must not include "<html"
          }
      }
    }

    "render without reaching a relay" in {
      // No timeout to assert on: the relay address points at a closed port, so anything that
      // called out would fail rather than answer, and these screens must not call out at all.
      withPhone(registered = true) { app =>
        for path <- screens do withClue(s"$path: ") { status(route(app, FakeRequest(GET, path)).get) mustBe OK }
      }
    }
  }

  "A phone with no identity yet" should {

    "show the sign-in gate instead of any screen it is asked for" in {
      withPhone(registered = false) { app =>
        for path <- screens do
          val result = route(app, FakeRequest(GET, path)).get
          withClue(s"$path: ") {
            status(result) mustBe OK
            contentAsString(result) must include("""name="pseudonym"""")
            contentAsString(result) must not include """<div id="screen""""
          }
      }
    }

    // The cheapest possible check that phon is mounted, and the one PhonRoutingSpec relies on:
    // GET /phonyPhone answers 200 whether or not anybody has registered.
    "still answer 200 at the root" in {
      withPhone(registered = false) { app =>
        status(route(app, FakeRequest(GET, "/phonyPhone")).get) mustBe OK
      }
    }
  }

  "Registering" should {

    "name the phone and land on the distributed tab" in {
      withPhone(registered = true) { app =>
        contentAsString(route(app, FakeRequest(GET, "/phonyPhone")).get) must include("Alice")
      }
    }
  }
}
