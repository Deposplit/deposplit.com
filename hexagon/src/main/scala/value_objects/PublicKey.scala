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

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/** Raw bytes of an Ed25519 public key (32 bytes), base64url-encoded on the wire. */
opaque type PublicKey = Array[Byte]

object PublicKey:
  private val KeyLength = 32
  private val decoder = Base64.getUrlDecoder
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def fromBase64Url(s: String): Either[String, PublicKey] =
    try
      val bytes = decoder.decode(s)
      if bytes.length != KeyLength then Left(s"Ed25519 public key must be $KeyLength bytes")
      else Right(bytes)
    catch case _: IllegalArgumentException => Left(s"invalid base64url: $s")

  extension (pk: PublicKey)
    def toBase64Url: String = encoder.encodeToString(pk)

    /** Verifies an Ed25519 `signature` over `message`. Returns false on any error. */
    def verify(message: Array[Byte], signature: Signature): Boolean =
      try
        val verifier = new Ed25519Signer()
        verifier.init(false, new Ed25519PublicKeyParameters(pk, 0))
        verifier.update(message, 0, message.length)
        verifier.verifySignature(signature.toBytes)
      catch case _: Exception => false
