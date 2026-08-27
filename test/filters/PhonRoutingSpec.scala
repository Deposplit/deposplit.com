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

package filters

import org.scalatestplus.play.*
import play.api.Mode
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.routing.Router
import play.api.test.*
import play.api.test.Helpers.*

/** phon is a teaching and manual-testing tool. It is mounted only by the development
  * router (`conf/dev.routes`), and its Guice bindings live in `PhonModule`, which only
  * `conf/localhost.conf` enables.
  *
  * Production therefore has no phon route, no phon controller and no phon binding. These
  * tests pin that down from both ends: production must not reach phon, and — the failure
  * that hid here for a long time — production must still be able to start.
  */
class PhonRoutingSpec extends PlaySpec {

  private val prodSecret = "play.http.secret.key" -> ("x" * 64)

  "The production router" should {

    "not serve the phon emulator" in {
      // /phonyPhone is not 404 at the router level: conf/routes ends with a catch-all that
      // hands any unmatched path to MarkdownController. What matters is that it resolves
      // to that catch-all rather than to phon, and so answers Not Found.
      val app = GuiceApplicationBuilder().build()
      try
        status(route(app, FakeRequest(GET, "/phonyPhone")).get) mustBe NOT_FOUND
        status(route(app, FakeRequest(GET, "/phonyPhone/contacts")).get) mustBe NOT_FOUND
      finally app.stop()
    }

    "still serve the landing page and the API" in {
      val app = GuiceApplicationBuilder().build()
      try
        status(route(app, FakeRequest(GET, "/")).get) mustBe OK
        // 401 rather than 404: the request reached AuthHelper, so it was routed to the API
        // controller and not swallowed by the catch-all.
        status(route(app, FakeRequest(GET, "/share-requests")).get) mustBe UNAUTHORIZED
      finally app.stop()
    }

    "contain no phon routes at all" in {
      val app = GuiceApplicationBuilder().build()
      try
        val phonRoutes = app.injector
          .instanceOf[Router]
          .documentation
          .filter { case (_, path, controller) =>
            path.contains("phonyPhone") || controller.contains("controllers.phon.")
          }
        withClue(s"production router unexpectedly exposes phon: $phonRoutes") {
          phonRoutes mustBe empty
        }
      finally app.stop()
    }
  }

  "A production-mode application without PhonModule" should {

    // The regression that mattered: conf/routes used to mount phon.Routes, so the router
    // needed PhonyPhoneController, whose ContactManagement/ShareManagement/
    // ForgettableIdentity dependencies are bound only in PhonModule — which
    // conf/application.conf never enables. Production could not start at all, which is
    // also why nothing ever reached the filter that was supposed to block phon.
    "start successfully" in {
      val app = GuiceApplicationBuilder()
        .in(Mode.Prod)
        .configure(prodSecret)
        .disable(Class.forName("PhonModule"))
        .build()
      try app.mode mustBe Mode.Prod
      finally app.stop()
    }

    "not serve the phon emulator" in {
      val app = GuiceApplicationBuilder()
        .in(Mode.Prod)
        .configure(prodSecret)
        .disable(Class.forName("PhonModule"))
        .build()
      try status(route(app, FakeRequest(GET, "/phonyPhone")).get) mustBe NOT_FOUND
      finally app.stop()
    }
  }

  "The development router" should {

    "serve the phon emulator" in {
      // Deliberately asserting OK, not merely that a route exists: conf/routes ends in a
      // catch-all, so a misordered dev router still "routes" /phonyPhone - to
      // MarkdownController, which answers Not Found. Only a 200 proves phon itself replied.
      val app = GuiceApplicationBuilder()
        .configure("play.http.router" -> "dev.Routes")
        .build()
      try status(route(app, FakeRequest(GET, "/phonyPhone")).get) mustBe OK
      finally app.stop()
    }

    "expose phon in its routing table" in {
      val app = GuiceApplicationBuilder()
        .configure("play.http.router" -> "dev.Routes")
        .build()
      try
        val phonRoutes = app.injector
          .instanceOf[Router]
          .documentation
          .filter { case (_, _, controller) => controller.startsWith("controllers.phon.") }
        phonRoutes must not be empty
      finally app.stop()
    }

    "still serve the landing page and the API" in {
      val app = GuiceApplicationBuilder()
        .configure("play.http.router" -> "dev.Routes")
        .build()
      try
        status(route(app, FakeRequest(GET, "/")).get) mustBe OK
        status(route(app, FakeRequest(GET, "/share-requests")).get) mustBe UNAUTHORIZED
      finally app.stop()
    }
  }
}
