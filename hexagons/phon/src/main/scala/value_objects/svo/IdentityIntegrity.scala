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

/** Whether this device can still act as the identity it believes it has.
  *
  * The question exists because local files and private keys do not travel together. App storage migrates to a new
  * phone; keys held by the platform keystore do not, and public key material kept beside them may. A restored device
  * can therefore believe it is registered, advertise the right public keys, and be unable to sign or decrypt as them.
  *
  * `Unreadable` is not padding. Key storage that is merely locked must never be mistaken for key storage that is empty:
  * `KeysLost` is what offers to mint a new identity, and doing that over an identity that was only temporarily
  * unreadable would destroy a working one.
  *
  * phon cannot reach `KeysLost` or `Unreadable` — its `FileIdentityStore` keeps keys and state in the same files, so
  * the two cannot come apart. The state is modelled here anyway, because the mobile apps share this vocabulary.
  */
enum IdentityIntegrity:
  /** The private keys are present and match the advertised public keys. Nothing to say. */
  case Intact

  /** This device is registered, but the private keys are gone or no longer match. Everything else — contacts, secrets,
    * share metadata, the shares held for other people — is untouched.
    */
  case KeysLost

  /** Key storage cannot be read at this moment. Say nothing, change nothing, ask again later. */
  case Unreadable
