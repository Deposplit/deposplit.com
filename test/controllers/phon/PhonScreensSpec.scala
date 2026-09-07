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
    "heldshares",
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

  // A contact is the prerequisite for a deposit, so these suites need one without a camera or a QR
  // scan: the manual entry path takes two keys and nothing else, which is all the deposit gate reads.
  private def addContact(app: Application, pseudonym: String, seed: Int): Unit =
    val result = route(
      app,
      FakeRequest(POST, "/phonyPhone/contacts")
        .withFormUrlEncodedBody(
          "pseudonym" -> pseudonym,
          "verifyKey" -> QrPayload.encodeKey(Array.fill(32)(seed.toByte)),
          "encKey" -> QrPayload.encodeKey(Array.fill(32)((seed + 1).toByte)),
          "verificationLevel" -> "High"
        )
        .withCSRFToken
    ).get
    status(result) mustBe SEE_OTHER

  private val screens = List(
    "/phonyPhone/distributed",
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

  "The document htmx runs inside" should {

    // Every mutating control in phon is an hx-post or an hx-delete, and none of them carries a hidden
    // token: they all rely on inheriting one header from the body. htmx 4 ships implicitInheritance
    // false, so the inheritance has to be asked for by name - and a bare hx-headers, which is what
    // htmx 2 wanted, leaves every button in the emulator failing CSRF while every screen still renders.
    "hand the CSRF token down to the controls that inherit it" in {
      withPhone(registered = true) { app =>
        val document = contentAsString(route(app, FakeRequest(GET, "/phonyPhone/distributed")).get)
        document must include("""hx-headers:inherited='{"Csrf-Token": """")
      }
    }
  }

  "Splitting a secret" should {

    // Splitting needs k of n with k at least 2, so one contact is as moot as none: the form used to be
    // offered anyway and then refuse the submission, which is a refusal the user could not have
    // predicted from what was on screen.
    "stay unoffered until there are two contacts to split among" in {
      withPhone(registered = true) { app =>
        contentAsString(route(app, FakeRequest(GET, "/phonyPhone/deposit")).get) must not include """name="label""""
        addContact(app, "Bob", 0x11)
        contentAsString(route(app, FakeRequest(GET, "/phonyPhone/deposit")).get) must not include """name="label""""
      }
    }

    "offer the form once a second contact exists" in {
      withPhone(registered = true) { app =>
        addContact(app, "Bob", 0x11)
        addContact(app, "Carol", 0x21)
        contentAsString(route(app, FakeRequest(GET, "/phonyPhone/deposit")).get) must include("""name="label"""")
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

    // The cheapest possible check that phon is mounted at all, and the one PhonRoutingSpec leans on:
    // before registration the root answers the gate itself rather than redirecting to a tab, so a 200
    // here needs no session and no identity.
    "still answer 200 at the root" in {
      withPhone(registered = false) { app =>
        status(route(app, FakeRequest(GET, "/phonyPhone")).get) mustBe OK
      }
    }
  }

  "Registering" should {

    "name the phone and land on the distributed tab" in {
      withPhone(registered = true) { app =>
        contentAsString(route(app, FakeRequest(GET, "/phonyPhone/distributed")).get) must include("Alice")
      }
    }

    // The root is a redirect once there is an identity, so that a bookmark on it lands on a tab
    // rather than on a shell with no tab selected. Before registration it still answers the gate.
    "send a registered phone from the root to that tab" in {
      withPhone(registered = true) { app =>
        val result = route(app, FakeRequest(GET, "/phonyPhone")).get
        status(result) mustBe TEMPORARY_REDIRECT
        redirectLocation(result) mustBe Some("/phonyPhone/distributed")
      }
    }
  }
}
