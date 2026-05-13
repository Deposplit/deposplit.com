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

class NonceTests extends munit.FunSuite:

  test("valid nonce is parsed") {
    val ms = Instant.now().toEpochMilli
    assert(Nonce(s"$ms.abc123").isDefined)
  }

  test("nonce without dot is rejected") {
    assertEquals(Nonce("1234567890"), None)
  }

  test("nonce with non-numeric timestamp is rejected") {
    assertEquals(Nonce("abc.xyz"), None)
  }

  test("nonce with empty random part is rejected") {
    assertEquals(Nonce(".123"), None)
  }

  test("recent nonce is not expired") {
    val ms = Instant.now().toEpochMilli
    val nonce = Nonce(s"$ms.abc").get
    assert(!nonce.isExpired)
  }

  test("nonce older than 5 minutes is expired") {
    val ms = Instant.now().toEpochMilli - 6 * 60 * 1000L
    val nonce = Nonce(s"$ms.abc").get
    assert(nonce.isExpired)
  }

  test("nonce from the future is expired") {
    val ms = Instant.now().toEpochMilli + 10 * 60 * 1000L
    val nonce = Nonce(s"$ms.abc").get
    assert(nonce.isExpired)
  }

  test("value round-trips") {
    val ms = Instant.now().toEpochMilli
    val raw = s"$ms.abc"
    val nonce = Nonce(raw).get
    assertEquals(nonce.value, raw)
  }
