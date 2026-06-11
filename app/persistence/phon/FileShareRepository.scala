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

package persistence.phon

import driven_ports.ShareRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import play.api.Configuration
import play.api.Logging
import value_objects.svo.HeldShare

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import scala.collection.mutable.ListBuffer

@Singleton
class FileShareRepository @Inject() (config: Configuration) extends ShareRepository, Logging:

  private val httpPort = config.getOptional[Int]("http.port").getOrElse(9000)
  private val file = File(s"./.devDBs/heldshares${httpPort}.ser")
  private var shares = ListBuffer.empty[HeldShare]

  if file.exists then
    // claude --resume 74829c30-8a8c-4097-a843-7e7ab067579b
    val ois = new ObjectInputStream(FileInputStream(file)):
      override def resolveClass(desc: java.io.ObjectStreamClass): Class[?] =
        try Class.forName(desc.getName, false, Thread.currentThread.getContextClassLoader)
        catch case _: ClassNotFoundException => super.resolveClass(desc)
    shares = ois.readObject().asInstanceOf[ListBuffer[HeldShare]]
    ois.close()
  end if

  private def serializeShares(): Unit =
    val createdNewFile = file.createNewFile()
    if createdNewFile then logger.info(s"file $file created") else logger.info(s"file $file not created again")
    val oos = ObjectOutputStream(FileOutputStream(file))
    oos.writeObject(shares)
    oos.close()

  override def delete(shareId: UUID): Unit =
    shares.filterInPlace(_.id != shareId)
    serializeShares()

  override def getAll(): List[HeldShare] = shares.toList

  override def getCiphertext(shareId: UUID): Option[Array[Byte]] = shares.find(_.id == shareId).map(_.ciphertext)

  override def save(share: HeldShare): Unit =
    shares += share
    serializeShares()
