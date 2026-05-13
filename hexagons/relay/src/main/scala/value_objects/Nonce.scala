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

package value_objects

import java.time.Instant
import scala.util.Try

/** Per-request nonce in the form `<unix-ms>.<random>`. */
opaque type Nonce = String

object Nonce:
  private val MaxAgeMs = 5 * 60 * 1000L

  /** Returns `Some(nonce)` if `s` is in the form `<unix-ms>.<random>`, `None` otherwise. */
  def apply(s: String): Option[Nonce] =
    val dot = s.indexOf('.')
    if dot <= 0 then None
    else Try(s.substring(0, dot).toLong).toOption.map(_ => s)

  extension (n: Nonce)
    def value: String = n

    /** True if the nonce's embedded timestamp is outside the 5-minute acceptance window. */
    def isExpired: Boolean =
      val ms = n.substring(0, n.indexOf('.')).toLong
      val age = Instant.now().toEpochMilli - ms
      age < 0 || age > MaxAgeMs
