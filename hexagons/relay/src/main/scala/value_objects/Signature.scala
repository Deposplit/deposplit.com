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

/** Base64url-decoded bytes of a signature.
  *
  * Not pinned to an exact length — see deposplit.com/CLAUDE.md item 14 ("variable-length keys"): a future signing
  * algorithm will not share Ed25519's 64-byte signature size, so only a generous sanity bound is enforced here.
  */
opaque type Signature = Array[Byte]

object Signature:
  // A sanity bound only, not algorithm-exact — see the type doc above. Not sized for any specific
  // future algorithm; revisit once one is actually chosen (deposplit.com/CLAUDE.md item 14).
  private val MaxLength = 128
  private val decoder = Base64.getUrlDecoder
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def fromBase64Url(s: String): Either[String, Signature] =
    try
      val bytes = decoder.decode(s)
      if bytes.isEmpty || bytes.length > MaxLength then Left(s"signature must be 1..$MaxLength bytes")
      else Right(bytes)
    catch case _: IllegalArgumentException => Left(s"invalid base64url: $s")

  def fromBytes(bytes: Array[Byte]): Either[String, Signature] =
    if bytes.isEmpty || bytes.length > MaxLength then Left(s"signature must be 1..$MaxLength bytes")
    else Right(bytes)

  extension (sig: Signature)
    def toBytes: Array[Byte] = sig
    def toBase64Url: String = encoder.encodeToString(sig)
