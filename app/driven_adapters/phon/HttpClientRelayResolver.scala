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

package driven_adapters.phon

import driven_ports.ShareRelay
import driven_ports.ShareRelayResolver
import driving_ports.Identity
import jakarta.inject.Inject

import java.util.concurrent.ConcurrentHashMap

/** phon is a manual-testing dev tool, not a real device — it has no persisted "default relay" setting (unlike
  * Android/iOS's runtime-configurable default). It hardcodes the same `localhost:9000` default `HttpClientShareRelay`
  * always used, but still honors a per-contact `relayBaseUrl` override so BYOR can be exercised in interop testing
  * (e.g. two local `sbt run` instances on different ports).
  */
class HttpClientRelayResolver @Inject() (identity: Identity) extends ShareRelayResolver:

  private val DefaultBaseUrl = "http://localhost:9000"
  private val cache = ConcurrentHashMap[String, ShareRelay]()

  override def resolve(relayBaseUrl: Option[String]): ShareRelay =
    val url = relayBaseUrl.getOrElse(DefaultBaseUrl)
    cache.computeIfAbsent(url, u => HttpClientShareRelay(identity, u))
