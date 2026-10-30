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

package value_objects.svo

import java.net.URI
import java.util.Locale
import scala.util.Try

/** What names a relay: an `http` or `https` URL with a host, and nothing after its path.
  *
  * Every request is this string with an endpoint's path appended, so a query or a fragment would swallow that path, and
  * a trailing slash would double the one it starts with. Anything else — `172.22.114.13:9000` without its scheme, a
  * `file:` URL — is refused where it is entered rather than stored, because once stored it fails on every sync for as
  * long as the contact exists.
  *
  * The mobile apps' `RelayBaseUrl` applies the same rule.
  */
object RelayBaseUrl:

  /** The relay `input` names, stripped and without trailing slashes, or `None` when it names none. */
  def parse(input: String): Option[String] =
    val stripped = input.strip()
    Try(URI(stripped)).toOption
      .filter(uri => Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT)).exists(Set("http", "https")))
      .filter(uri => Option(uri.getHost).exists(_.nonEmpty))
      .filter(uri => uri.getRawUserInfo == null && uri.getRawQuery == null && uri.getRawFragment == null)
      .map(_ => stripped.replaceFirst("/+$", ""))
