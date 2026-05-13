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

import java.util.Base64

class SignatureTests extends munit.FunSuite:

  private val encoder = Base64.getUrlEncoder.withoutPadding

  test("invalid base64url is rejected") {
    assert(Signature.fromBase64Url("not!!valid@@base64").isLeft)
  }

  test("fewer than 64 bytes is rejected") {
    val short = encoder.encodeToString(Array.fill(32)(0x01.toByte))
    assert(Signature.fromBase64Url(short).isLeft)
  }

  test("more than 64 bytes is rejected") {
    val long = encoder.encodeToString(Array.fill(65)(0x01.toByte))
    assert(Signature.fromBase64Url(long).isLeft)
  }

  test("exactly 64 bytes is accepted") {
    val valid = encoder.encodeToString(Array.fill(64)(0x02.toByte))
    assert(Signature.fromBase64Url(valid).isRight)
  }

  test("toBytes round-trips") {
    val bytes = Array.fill(64)(0x03.toByte)
    val sig = Signature.fromBase64Url(encoder.encodeToString(bytes)).getOrElse(fail("parse failed"))
    assert(java.util.Arrays.equals(sig.toBytes, bytes))
  }
