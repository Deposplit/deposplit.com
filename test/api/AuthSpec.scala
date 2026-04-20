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

package api

import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import play.api.test.*
import play.api.test.Helpers.*

/** Verifies AuthHelper header validation against GET /shares (simplest authenticated endpoint). */
class AuthSpec extends PlaySpec with GuiceOneAppPerSuite:

  // All auth tests target this endpoint; the response body is irrelevant.
  private val path = "/shares?role=sender"

  "AuthHelper" should {

    "reject a request with no auth headers" in {
      val result = route(app, FakeRequest("GET", path)).get
      status(result) mustBe UNAUTHORIZED
    }

    "reject a request with a missing nonce" in {
      val signer = new RequestSigner()
      val result = route(app, FakeRequest("GET", path).withHeaders(
        "X-Deposplit-Public-Key" -> signer.publicKeyHeader,
        "X-Deposplit-Signature"  -> "placeholder"
      )).get
      status(result) mustBe UNAUTHORIZED
    }

    "reject a request with a missing signature" in {
      val signer = new RequestSigner()
      val result = route(app, FakeRequest("GET", path).withHeaders(
        "X-Deposplit-Public-Key" -> signer.publicKeyHeader,
        "X-Deposplit-Nonce"      -> s"${System.currentTimeMillis()}.abc"
      )).get
      status(result) mustBe UNAUTHORIZED
    }

    "reject a request with an expired nonce" in {
      // Nonce timestamp is 10 minutes in the past — outside the 5-minute window.
      // All three headers must be present; the expiry check happens before signature parsing.
      val signer    = new RequestSigner()
      val expiredMs = System.currentTimeMillis() - 10 * 60 * 1000L
      val result = route(app, FakeRequest("GET", path).withHeaders(
        "X-Deposplit-Public-Key" -> signer.publicKeyHeader,
        "X-Deposplit-Nonce"      -> s"$expiredMs.abc",
        "X-Deposplit-Signature"  -> "placeholder"
      )).get
      status(result) mustBe UNAUTHORIZED
    }

    "reject a request with an invalid signature" in {
      // 86 base64url 'A' chars = 64 zero-bytes: valid length for Ed25519 but won't verify.
      val signer   = new RequestSigner()
      val wrongSig = "A" * 86
      val result = route(app, FakeRequest("GET", path).withHeaders(
        "X-Deposplit-Public-Key" -> signer.publicKeyHeader,
        "X-Deposplit-Nonce"      -> s"${System.currentTimeMillis()}.abc",
        "X-Deposplit-Signature"  -> wrongSig
      )).get
      status(result) mustBe UNAUTHORIZED
    }

    "accept a request with a valid signature" in {
      val signer = new RequestSigner()
      val result = route(app, signer.get(path)).get
      status(result) mustBe OK
    }
  }
