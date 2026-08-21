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

package driving_adapters

import driven_ports.ForgettableIdentityStore
import driving_adapters.ShareEncryption
import driving_ports.ForgettableIdentity
import jakarta.inject.Inject
import value_objects.svo.KeyPairMaterial
import value_objects.svo.TransportSuite
import value_objects.svo.UnsupportedTransportSuiteException
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

class IdentityService @Inject() (identityStore: ForgettableIdentityStore) extends ForgettableIdentity, ShareEncryption:

  override def isRegistered(): Boolean = identityStore.isRegistered()

  override def register(pseudonym: String): Unit =
    val material = generateKeyPairMaterial()
    identityStore.save(pseudonym, material.verifyKey, material.signKey, material.encKey, material.decKey)

  override def generateNewKeyPair(): KeyPairMaterial = generateKeyPairMaterial()

  override def activateKeyPair(keyPair: KeyPairMaterial): Unit =
    identityStore.save(identityStore.pseudonym(), keyPair.verifyKey, keyPair.signKey, keyPair.encKey, keyPair.decKey)

  private def generateKeyPairMaterial(): KeyPairMaterial =
    val random = SecureRandom()

    val edGen = Ed25519KeyPairGenerator()
    edGen.init(Ed25519KeyGenerationParameters(random))
    val edPair = edGen.generateKeyPair()
    val verifyKey = edPair.getPublic.asInstanceOf[Ed25519PublicKeyParameters].getEncoded
    val signKey = edPair.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters].getEncoded

    val xGen = X25519KeyPairGenerator()
    xGen.init(X25519KeyGenerationParameters(random))
    val xPair = xGen.generateKeyPair()
    val encKey = xPair.getPublic.asInstanceOf[X25519PublicKeyParameters].getEncoded
    val decKey = xPair.getPrivate.asInstanceOf[X25519PrivateKeyParameters].getEncoded

    KeyPairMaterial(verifyKey, signKey, encKey, decKey)

  override def unregister() = identityStore.forget()

  override def pseudonym(): String = identityStore.pseudonym()

  override def verifyKey(): Array[Byte] = identityStore.verifyKey()

  override def encKey(): Array[Byte] = identityStore.encKey()

  override def sign(message: Array[Byte]): Array[Byte] =
    val sk = Ed25519PrivateKeyParameters(identityStore.signKey())
    val signer = Ed25519Signer()
    signer.init(true, sk)
    signer.update(message, 0, message.length)
    signer.generateSignature()

  override def verify(message: Array[Byte], signature: Array[Byte], publicKey: Array[Byte]): Boolean =
    try
      val verifier = Ed25519Signer()
      verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
      verifier.update(message, 0, message.length)
      verifier.verifySignature(signature)
    catch case _: Exception => false

  // Item 14 — wire format is now suiteTag(1) || nonce(12) || ciphertext+tag. No persistent state
  // needed: a device always encrypts with its current preferred TransportSuite, and a decrypting
  // device dispatches on the tag it reads.
  override def encrypt(plaintext: Array[Byte], recipientXPublicKey: Array[Byte]): Array[Byte] =
    val sk = X25519PrivateKeyParameters(identityStore.decKey())
    val nonce = Array.ofDim[Byte](IdentityService.NonceBytes)
    IdentityService.secureRandom.nextBytes(nonce)
    val key = deriveKey(sk, X25519PublicKeyParameters(recipientXPublicKey), nonce)
    val cipher = ChaCha20Poly1305()
    cipher.init(true, AEADParameters(KeyParameter(key), IdentityService.TagBits, nonce))
    val out = Array.ofDim[Byte](cipher.getOutputSize(plaintext.length))
    var len = cipher.processBytes(plaintext, 0, plaintext.length, out, 0)
    len += cipher.doFinal(out, len)
    Array(TransportSuite.current.tag) ++ nonce ++ out.take(len)

  override def decrypt(noncePlusCiphertext: Array[Byte], recipientXPublicKey: Array[Byte]): Array[Byte] =
    val tag = noncePlusCiphertext.headOption.getOrElse(
      throw UnsupportedTransportSuiteException("ciphertext is empty — no transport suite tag")
    )
    if TransportSuite.fromTag(tag).isEmpty then
      throw UnsupportedTransportSuiteException("this share used an encryption scheme this app version doesn't support")
    val sk = X25519PrivateKeyParameters(identityStore.decKey())
    val nonce = noncePlusCiphertext.slice(1, 1 + IdentityService.NonceBytes)
    val ciphertext = noncePlusCiphertext.drop(1 + IdentityService.NonceBytes)
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
