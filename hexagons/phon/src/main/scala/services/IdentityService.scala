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

package services

import driven_ports.ForgettableIdentityStore
import driving_ports.ForgettableIdentity
import driving_ports.RequestSigner
import jakarta.inject.Inject
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

import java.security.SecureRandom

class IdentityService @Inject() (identityStore: ForgettableIdentityStore)
    extends ForgettableIdentity,
      ShareEncryption,
      RequestSigner:

  override def isRegistered(): Boolean = identityStore.isRegistered()

  override def register(pseudonym: String): Unit =
    val random = SecureRandom()

    val edGen = Ed25519KeyPairGenerator()
    edGen.init(Ed25519KeyGenerationParameters(random))
    val edPair = edGen.generateKeyPair()
    val edPk = edPair.getPublic.asInstanceOf[Ed25519PublicKeyParameters].getEncoded
    val edSk = edPair.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters].getEncoded

    val xGen = X25519KeyPairGenerator()
    xGen.init(X25519KeyGenerationParameters(random))
    val xPair = xGen.generateKeyPair()
    val xPk = xPair.getPublic.asInstanceOf[X25519PublicKeyParameters].getEncoded
    val xSk = xPair.getPrivate.asInstanceOf[X25519PrivateKeyParameters].getEncoded

    identityStore.save(pseudonym, edPk, edSk, xPk, xSk)

  override def unregister() = identityStore.forget()
  
  override def pseudonym(): String = identityStore.pseudonym()

  override def edPublicKey(): Array[Byte] = identityStore.edPublicKey()

  override def xPublicKey(): Array[Byte] = identityStore.xPublicKey()

  override def sign(message: Array[Byte]): Array[Byte] =
    val sk = Ed25519PrivateKeyParameters(identityStore.edPrivateKey())
    val signer = Ed25519Signer()
    signer.init(true, sk)
    signer.update(message, 0, message.length)
    signer.generateSignature()

  override def encrypt(plaintext: Array[Byte], recipientXPublicKey: Array[Byte]): Array[Byte] =
    val sk = X25519PrivateKeyParameters(identityStore.xPrivateKey())
    val nonce = Array.ofDim[Byte](IdentityService.NonceBytes)
    IdentityService.secureRandom.nextBytes(nonce)
    val key = deriveKey(sk, X25519PublicKeyParameters(recipientXPublicKey), nonce)
    val cipher = ChaCha20Poly1305()
    cipher.init(true, AEADParameters(KeyParameter(key), IdentityService.TagBits, nonce))
    val out = Array.ofDim[Byte](cipher.getOutputSize(plaintext.length))
    var len = cipher.processBytes(plaintext, 0, plaintext.length, out, 0)
    len += cipher.doFinal(out, len)
    nonce ++ out.take(len)

  override def decrypt(noncePlusCiphertext: Array[Byte], recipientXPublicKey: Array[Byte]): Array[Byte] =
    val sk = X25519PrivateKeyParameters(identityStore.xPrivateKey())
    val nonce = noncePlusCiphertext.take(IdentityService.NonceBytes)
    val ciphertext = noncePlusCiphertext.drop(IdentityService.NonceBytes)
    val key = deriveKey(sk, X25519PublicKeyParameters(recipientXPublicKey), nonce)
    val cipher = ChaCha20Poly1305()
    cipher.init(false, AEADParameters(KeyParameter(key), IdentityService.TagBits, nonce))
    val out = Array.ofDim[Byte](cipher.getOutputSize(ciphertext.length))
    var len = cipher.processBytes(ciphertext, 0, ciphertext.length, out, 0)
    len += cipher.doFinal(out, len)
    out.take(len)

  private def deriveKey(
      sk: X25519PrivateKeyParameters,
      pk: X25519PublicKeyParameters,
      nonce: Array[Byte]
  ): Array[Byte] =
    val agreement = X25519Agreement()
    agreement.init(sk)
    val sharedSecret = Array.ofDim[Byte](agreement.getAgreementSize)
    agreement.calculateAgreement(pk, sharedSecret, 0)
    val hkdf = HKDFBytesGenerator(SHA256Digest())
    hkdf.init(HKDFParameters(sharedSecret, nonce, IdentityService.HkdfInfo))
    val key = Array.ofDim[Byte](IdentityService.KeyBytes)
    hkdf.generateBytes(key, 0, IdentityService.KeyBytes)
    key

object IdentityService:
  private val NonceBytes = 12
  private val KeyBytes = 32
  private val TagBits = 128
  private val HkdfInfo = "deposplit-share".getBytes("UTF-8")
  private val secureRandom = SecureRandom()
