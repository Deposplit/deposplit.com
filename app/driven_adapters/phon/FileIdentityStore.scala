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

package driven_adapters.phon

import jakarta.inject.Inject
import jakarta.inject.Singleton
import play.api.Configuration
import play.api.Logging

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import driven_ports.ForgettableIdentityStore

case class DevIdentity(
    pseudonym: String,
    edPrivateKey: Array[Byte],
    edPublicKey: Array[Byte],
    xPrivateKey: Array[Byte],
    xPublicKey: Array[Byte]
) extends Serializable

@Singleton
class FileIdentityStore @Inject() (config: Configuration) extends ForgettableIdentityStore, Logging:

  private val httpPort = config.getOptional[Int]("http.port").getOrElse(9000)
  private val file = File(s"./.devDBs/identity${httpPort}.ser")
  private var optionalIdentity: Option[DevIdentity] = None

  if file.exists then
    // claude --resume 74829c30-8a8c-4097-a843-7e7ab067579b
    val ois = ObjectInputStream(FileInputStream(file))
    val devIdentity = ois.readObject().asInstanceOf[DevIdentity]
    ois.close()
    optionalIdentity = Option(devIdentity)
  end if

  override def edPrivateKey(): Array[Byte] = optionalIdentity.get.edPrivateKey

  override def edPublicKey(): Array[Byte] = optionalIdentity.get.edPublicKey

  override def isRegistered(): Boolean = optionalIdentity.isDefined

  override def pseudonym(): String = optionalIdentity.get.pseudonym

  override def save(pseudonym: String, edPk: Array[Byte], edSk: Array[Byte], xPk: Array[Byte], xSk: Array[Byte]): Unit =
    val devIdentity = DevIdentity(pseudonym, edSk, edPk, xSk, xPk)

    val createdNewFile = file.createNewFile()
    if createdNewFile then logger.info(s"file $file created") else logger.info(s"file $file not created again")
    val oos = ObjectOutputStream(FileOutputStream(file))
    oos.writeObject(devIdentity)
    oos.close()

    optionalIdentity = Option(devIdentity)

  override def xPrivateKey(): Array[Byte] = optionalIdentity.get.xPrivateKey

  override def xPublicKey(): Array[Byte] = optionalIdentity.get.xPublicKey

  override def forget() =
    optionalIdentity = None
    file.delete()
