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

package driving_ports

import java.util.UUID
import value_objects.svo.Contact

trait ContactManagement:
  def listContacts(): List[Contact]
  def addManually(pseudonym: String, edPublicKey: Array[Byte], xPublicKey: Array[Byte], relayBaseUrl: Option[String] = None): Unit
  def addFromQr(pseudonym: String, edPublicKey: Array[Byte], xPublicKey: Array[Byte], relayBaseUrl: Option[String] = None): Unit
  /** Updates an existing contact in place, preserving contactId — never delete-and-re-add, which
    * would mint a fresh id and orphan any HeldShare/ShareMetadata rows anchored to it. See
    * deposplit.com/CLAUDE.md "What is next" item 8.
    */
  def updateContact(contactId: UUID, edPublicKey: Option[Array[Byte]] = None, xPublicKey: Option[Array[Byte]] = None): Unit
  def deleteContact(contactId: UUID): Unit
