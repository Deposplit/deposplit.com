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

import jakarta.inject.Inject
import org.apache.pekko.stream.Materializer
import play.api.Environment
import play.api.mvc.Filter
import play.api.mvc.RequestHeader
import play.api.mvc.Result
import play.api.mvc.Results
import play.api.http.Status

import scala.concurrent.Future

/** Keeps the REST API on `api.` and everything else on `www.`.
  *
  * A request that arrives on the wrong host is redirected to the right one rather than
  * served, so there is exactly one canonical host per surface.
  *
  * The API surface is enumerated in [[ApiHostPathFilter.apiPathPrefixes]]. That list must
  * cover every path routed to a `controllers.api.*` action — a path missing from it is
  * redirected off `api.` and away from the endpoint the native apps actually call.
  * `ApiHostPathFilterSpec` asserts the list against the router, so adding an endpoint
  * without updating it fails the build rather than production.
  */
class ApiHostPathFilter @Inject() (implicit val mat: Materializer, env: Environment) extends Filter:

  import ApiHostPathFilter.isApiPath

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] =
    val scheme = if requestHeader.secure then "https" else "http"
    val pathAndQuery =
      if requestHeader.rawQueryString.isBlank then requestHeader.path
      else s"${requestHeader.path}?${requestHeader.rawQueryString}"
    val onApiHost = requestHeader.host.startsWith("api.")
    val wantsApi = isApiPath(requestHeader.path)

    if onApiHost && !wantsApi then
      // Browsing traffic that landed on api.: send it to www. A 303 is right here — these
      // are GETs from a browser, and the redirect should not carry a method or body.
      val newHost = requestHeader.host.replaceFirst("api\\.", "www.")
      Future.successful(Results.Redirect(s"$scheme://$newHost$pathAndQuery"))
    else if !onApiHost && wantsApi then
      // An API call that landed on www. Redirect with 308, not 303: these are POSTs,
      // PATCHes and DELETEs from the native apps, and a 303 would silently downgrade them
      // to GET and drop the body.
      val newHost = "api." + requestHeader.host.replaceFirst("www\\.", "")
      Future.successful(Results.Redirect(s"$scheme://$newHost$pathAndQuery", Status.PERMANENT_REDIRECT))
    else nextFilter(requestHeader)

object ApiHostPathFilter:

  /** Every path prefix belonging to the REST API, i.e. routed to a `controllers.api.*`
    * action. Kept in sync with `conf/routes` by `ApiHostPathFilterSpec`.
    */
  val apiPathPrefixes: Seq[String] =
    Seq("/share-requests", "/key-rotations", "/custody-heartbeats")

  def isApiPath(path: String): Boolean = apiPathPrefixes.exists(path.startsWith)
