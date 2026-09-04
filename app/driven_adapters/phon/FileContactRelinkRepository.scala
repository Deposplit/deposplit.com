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

import driven_ports.ContactRelinkRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import play.api.Configuration
import play.api.Logging
import value_objects.svo.ContactRelink

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import scala.collection.mutable.ListBuffer

// Which contacts are known to hold this device's current key. Only ever non-empty after an identity was
// re-established without the old key to sign a rotation notice with, which phon cannot actually reach — it keeps keys
// and state in the same file — so this exists for parity with the mobile apps rather than for phon's own sake.
@Singleton
class FileContactRelinkRepository @Inject() (config: Configuration) extends ContactRelinkRepository, Logging:

  private val httpPort = config.getOptional[Int]("http.port").getOrElse(9000)
  private val file = File(s"./.devDBs/contactrelinks${httpPort}.ser")
  private var relinks = ListBuffer.empty[ContactRelink]

  if file.exists then
    val ois = new ObjectInputStream(FileInputStream(file)):
      override def resolveClass(desc: java.io.ObjectStreamClass): Class[?] =
        try Class.forName(desc.getName, false, Thread.currentThread.getContextClassLoader)
        catch case _: ClassNotFoundException => super.resolveClass(desc)
    relinks = ois.readObject().asInstanceOf[ListBuffer[ContactRelink]]
    ois.close()
  end if

  private def serializeRelinks(): Unit =
    val createdNewFile = file.createNewFile()
    if createdNewFile then logger.info(s"file $file created") else logger.info(s"file $file not created again")
    val oos = ObjectOutputStream(FileOutputStream(file))
    oos.writeObject(relinks)
    oos.close()

  override def getAll(): List[ContactRelink] = relinks.toList

  override def get(contactId: UUID): Option[ContactRelink] = relinks.find(_.contactId == contactId)

  override def save(relink: ContactRelink): Unit =
    relinks = relinks.filterNot(_.contactId == relink.contactId)
    relinks += relink
    serializeRelinks()
