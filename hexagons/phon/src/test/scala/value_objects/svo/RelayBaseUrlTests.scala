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

/** The rule every way of naming a relay is checked against. The mobile apps' RelayBaseUrl tests hold the same cases, so
  * a relay one platform accepts another does not refuse.
  */
class RelayBaseUrlTests extends munit.FunSuite:

  test("accepts http and https with a host, a port and a path") {
    assertEquals(RelayBaseUrl.parse("https://api.deposplit.com"), Some("https://api.deposplit.com"))
    assertEquals(RelayBaseUrl.parse("http://172.22.114.13:9000"), Some("http://172.22.114.13:9000"))
    assertEquals(RelayBaseUrl.parse("https://example.org/deposplit"), Some("https://example.org/deposplit"))
    assertEquals(RelayBaseUrl.parse("http://[::1]:9000"), Some("http://[::1]:9000"))
    assertEquals(RelayBaseUrl.parse("HTTPS://Relay.Example"), Some("HTTPS://Relay.Example"))
  }

  // Every request appends a path that starts with a slash, so one left on the end would double it.
  test("trims whitespace and trailing slashes") {
    assertEquals(RelayBaseUrl.parse("  https://relay.example/ \n"), Some("https://relay.example"))
    assertEquals(RelayBaseUrl.parse("https://example.org/deposplit//"), Some("https://example.org/deposplit"))
  }

  test("refuses an address without an http scheme") {
    assertEquals(RelayBaseUrl.parse("172.22.114.13:9000"), None)
    assertEquals(RelayBaseUrl.parse("localhost:9000"), None)
    assertEquals(RelayBaseUrl.parse("relay.example"), None)
    assertEquals(RelayBaseUrl.parse("file:///etc/hosts"), None)
    assertEquals(RelayBaseUrl.parse("ftp://relay.example"), None)
  }

  test("refuses an address without a host") {
    assertEquals(RelayBaseUrl.parse(""), None)
    assertEquals(RelayBaseUrl.parse("http://"), None)
    assertEquals(RelayBaseUrl.parse("http:///share-requests"), None)
  }

  test("refuses user info, a query or a fragment") {
    assertEquals(RelayBaseUrl.parse("https://user:secret@relay.example"), None)
    assertEquals(RelayBaseUrl.parse("https://relay.example?region=eu"), None)
    assertEquals(RelayBaseUrl.parse("https://relay.example#top"), None)
  }

  test("refuses characters a URL cannot hold") {
    assertEquals(RelayBaseUrl.parse("https://relay example.com"), None)
    assertEquals(RelayBaseUrl.parse("https://bücher.example"), None)
  }
