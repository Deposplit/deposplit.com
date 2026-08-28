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
import org.scalatestplus.play.guice.*
import play.api.routing.Router
import play.api.test.*
import play.api.test.Helpers.*

/** `ApiHostPathFilter` splits one deployment across two hostnames: the REST API on `api.` and everything else on
  * `www.`.
  *
  * The audit below is the point of this spec. The filter classifies paths from a hardcoded list, and that list once
  * named only `/share-requests` — so when `/key-rotations` and `/custody-heartbeats` were added, both were redirected
  * off `api.` and away from the endpoint the native apps call. Deriving the expectation from the router means adding a
  * fourth endpoint without updating the list fails here instead of in production.
  */
class ApiHostPathFilterSpec extends PlaySpec with GuiceOneAppPerSuite {

  private def apiRoutes: Seq[(String, String, String)] =
    app.injector.instanceOf[Router].documentation.filter { case (_, _, controller) =>
      controller.startsWith("controllers.api.")
    }

  private def nonApiRoutes: Seq[(String, String, String)] =
    app.injector.instanceOf[Router].documentation.filterNot { case (_, _, controller) =>
      controller.startsWith("controllers.api.")
    }

  "The API path list" should {

    "not be empty" in {
      apiRoutes must not be empty
      ApiHostPathFilter.apiPathPrefixes must not be empty
    }

    "cover every route served by a controllers.api.* action" in {
      val uncovered = apiRoutes.filterNot { case (_, path, _) =>
        ApiHostPathFilter.isApiPath(path)
      }
      withClue(
        "these API routes are not classified as API, so they would be redirected off " +
          s"api. and break for the native apps: ${uncovered.mkString(", ")} — add their " +
          "prefix to ApiHostPathFilter.apiPathPrefixes: "
      ) {
        uncovered mustBe empty
      }
    }

    "not classify any non-API route as API" in {
      val misclassified = nonApiRoutes.filter { case (_, path, _) =>
        ApiHostPathFilter.isApiPath(path)
      }
      withClue(s"these non-API routes are classified as API: ${misclassified.mkString(", ")}: ") {
        misclassified mustBe empty
      }
    }

    "contain no stale prefix that matches nothing" in {
      val stale = ApiHostPathFilter.apiPathPrefixes.filterNot { prefix =>
        apiRoutes.exists { case (_, path, _) => path.startsWith(prefix) }
      }
      withClue(s"these prefixes match no route in conf/routes: ${stale.mkString(", ")}: ") {
        stale mustBe empty
      }
    }
  }

  "The filter" should {

    "classify each API surface" in {
      ApiHostPathFilter.isApiPath("/share-requests") mustBe true
      ApiHostPathFilter.isApiPath("/share-requests/withdraw") mustBe true
      ApiHostPathFilter.isApiPath("/key-rotations") mustBe true
      ApiHostPathFilter.isApiPath("/custody-heartbeats") mustBe true
    }

    "not classify the landing page or assets as API" in {
      ApiHostPathFilter.isApiPath("/") mustBe false
      ApiHostPathFilter.isApiPath("/assets/main.css") mustBe false
      ApiHostPathFilter.isApiPath("/origin") mustBe false
    }
  }
}
