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

/** A secret's label, sealed by the sender to the recipient's encryption key with the same box as a share. The relay
  * stores and returns these bytes without being able to read them. Every request type carries one, because the
  * recipient shows it.
  *
  * Only emptiness is refused. The relay cannot tell a well-formed box from any other bytes, and does not try to.
  */
opaque type SealedLabel = Array[Byte]

object SealedLabel:
  private val decoder = Base64.getDecoder
  private val encoder = Base64.getEncoder

  def fromBase64(s: String): Either[String, SealedLabel] =
    try fromBytes(decoder.decode(s))
    catch case _: IllegalArgumentException => Left("sealedLabel must be standard base64")

  def fromBytes(bytes: Array[Byte]): Either[String, SealedLabel] =
    if bytes.isEmpty then Left("sealedLabel must not be empty") else Right(bytes)

  extension (l: SealedLabel)
    def toBytes: Array[Byte] = l
    def toBase64: String = encoder.encodeToString(l)
