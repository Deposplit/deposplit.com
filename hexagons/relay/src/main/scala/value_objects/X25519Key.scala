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

/** Raw bytes of an X25519 public key (32 bytes), base64url-encoded on the wire.
  *
  * The relay never performs key agreement with it — like ciphertext, it only ever routes it as
  * an opaque 32-byte value (item 9's rotation push, see `KeyRotation`). A dedicated type, rather
  * than reusing `PublicKey` (which is documented and used for Ed25519 verification), keeps the
  * two key algorithms from being silently interchangeable at a call site despite sharing a byte
  * length.
  */
opaque type X25519Key = Array[Byte]

object X25519Key:
  private val KeyLength = 32
  private val decoder = Base64.getUrlDecoder
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def fromBase64Url(s: String): Either[String, X25519Key] =
    try
      val bytes = decoder.decode(s)
      if bytes.length != KeyLength then Left(s"X25519 public key must be $KeyLength bytes")
      else Right(bytes)
    catch case _: IllegalArgumentException => Left(s"invalid base64url: $s")

  def fromBytes(bytes: Array[Byte]): Either[String, X25519Key] =
    if bytes.length != KeyLength then Left(s"X25519 public key must be $KeyLength bytes")
    else Right(bytes)

  extension (k: X25519Key)
    def toBase64Url: String = encoder.encodeToString(k)
    def toBytes: Array[Byte] = k
