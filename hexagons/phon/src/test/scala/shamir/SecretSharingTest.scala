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

package shamir

import munit.FunSuite

class SecretSharingTest extends FunSuite:

  private def assertBytesEqual(actual: Array[Byte], expected: Array[Byte]): Unit =
    assertEquals(actual.toList, expected.toList)

  // ---------------------------------------------------------------------------
  // Round-trip tests
  // ---------------------------------------------------------------------------

  test("split then combine recovers secret for 2-of-3"):
    val secret = "Hello, Deposplit!".getBytes("UTF-8")
    val shares = SecretSharing.split(secret, shares = 3, threshold = 2)
    assertBytesEqual(SecretSharing.combine(shares.take(2)), secret)
    assertBytesEqual(SecretSharing.combine(shares.takeRight(2)), secret)
    assertBytesEqual(SecretSharing.combine(List(shares(0), shares(2))), secret)

  test("split then combine recovers secret for 3-of-5"):
    val secret = Array.tabulate[Byte](32)(_.toByte)
    val shares = SecretSharing.split(secret, shares = 5, threshold = 3)
    assertBytesEqual(SecretSharing.combine(shares.take(3)), secret)
    assertBytesEqual(SecretSharing.combine(shares.takeRight(3)), secret)
    assertBytesEqual(SecretSharing.combine(List(shares(0), shares(2), shares(4))), secret)

  test("split then combine recovers secret for 255-of-255"):
    val secret = Array[Byte](0x42)
    val shares = SecretSharing.split(secret, shares = 255, threshold = 255)
    assertBytesEqual(SecretSharing.combine(shares), secret)

  test("split then combine recovers single-byte secret"):
    val secret = Array[Byte](0xff.toByte)
    val shares = SecretSharing.split(secret, shares = 2, threshold = 2)
    assertBytesEqual(SecretSharing.combine(shares), secret)

  test("combining all shares also works when more than threshold are provided"):
    val secret = "extra shares are fine".getBytes("UTF-8")
    val shares = SecretSharing.split(secret, shares = 5, threshold = 3)
    assertBytesEqual(SecretSharing.combine(shares), secret)

  // ---------------------------------------------------------------------------
  // Cross-platform test vectors
  //
  // These vectors are derived by hand from the GF(2^8) arithmetic and verify
  // that combine() is implemented correctly. They use the polynomial
  // f(x) = secret_byte + 0x01·x in GF(2^8) with x-coordinates [1, 2],
  // which is the simplest non-trivial 2-of-2 case (threshold = 2, degree = 1,
  // leading coefficient c₁ = 0x01).
  //
  // These same vectors are used in the Kotlin and Swift ports to confirm
  // byte-for-byte cross-platform compatibility of combine().
  // ---------------------------------------------------------------------------

  test("cross-platform vector 1 - zero secret byte"):
    // secret = [0x00]
    // f(x) = 0x00 + 0x01·x  →  f(1) = 0x01, f(2) = 0x02
    val shares = List(
      Array[Byte](0x01, 0x01), // y = 0x01, x = 0x01
      Array[Byte](0x02, 0x02), // y = 0x02, x = 0x02
    )
    assertBytesEqual(SecretSharing.combine(shares), Array[Byte](0x00))

  test("cross-platform vector 2 - non-zero secret byte"):
    // secret = [0x41]  ('A')
    // f(x) = 0x41 + 0x01·x  →  f(1) = 0x40, f(2) = 0x43
    val shares = List(
      Array[Byte](0x40, 0x01), // y = 0x40, x = 0x01
      Array[Byte](0x43, 0x02), // y = 0x43, x = 0x02
    )
    assertBytesEqual(SecretSharing.combine(shares), Array[Byte](0x41))

  test("cross-platform vector 3 - multi-byte secret"):
    // secret = [0x00, 0x41]
    // Byte 0: f(x) = 0x00 + 0x01·x  →  f(1) = 0x01, f(2) = 0x02
    // Byte 1: f(x) = 0x41 + 0x01·x  →  f(1) = 0x40, f(2) = 0x43
    val shares = List(
      Array[Byte](0x01, 0x40, 0x01), // [y₀, y₁, x] for x = 0x01
      Array[Byte](0x02, 0x43, 0x02), // [y₀, y₁, x] for x = 0x02
    )
    assertBytesEqual(SecretSharing.combine(shares), Array[Byte](0x00, 0x41))

  // ---------------------------------------------------------------------------
  // Input validation — split()
  // ---------------------------------------------------------------------------

  test("split rejects empty secret"):
    intercept[IllegalArgumentException]:
      SecretSharing.split(Array.empty[Byte], shares = 2, threshold = 2)

  test("split rejects shares below 2"):
    intercept[IllegalArgumentException]:
      SecretSharing.split(Array[Byte](0x01), shares = 1, threshold = 1)

  test("split rejects shares above 255"):
    intercept[IllegalArgumentException]:
      SecretSharing.split(Array[Byte](0x01), shares = 256, threshold = 2)

  test("split rejects threshold below 2"):
    intercept[IllegalArgumentException]:
      SecretSharing.split(Array[Byte](0x01), shares = 2, threshold = 1)

  test("split rejects threshold above 255"):
    intercept[IllegalArgumentException]:
      SecretSharing.split(Array[Byte](0x01), shares = 255, threshold = 256)

  test("split rejects threshold greater than shares"):
    intercept[IllegalArgumentException]:
      SecretSharing.split(Array[Byte](0x01), shares = 2, threshold = 3)

  // ---------------------------------------------------------------------------
  // Input validation — combine()
  // ---------------------------------------------------------------------------

  test("combine rejects fewer than 2 shares"):
    intercept[IllegalArgumentException]:
      SecretSharing.combine(List(Array[Byte](0x01, 0x02)))

  test("combine rejects shares shorter than 2 bytes"):
    intercept[IllegalArgumentException]:
      SecretSharing.combine(List(Array[Byte](0x01), Array[Byte](0x02)))

  test("combine rejects shares with mismatched lengths"):
    intercept[IllegalArgumentException]:
      SecretSharing.combine(List(Array[Byte](0x01, 0x02), Array[Byte](0x01, 0x02, 0x03)))

  test("combine rejects duplicate x-coordinates"):
    // Both shares have x = 0x05 (last byte)
    intercept[IllegalArgumentException]:
      SecretSharing.combine(List(Array[Byte](0x01, 0x05), Array[Byte](0x02, 0x05)))
