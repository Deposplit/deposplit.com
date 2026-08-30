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

import driven_ports.KeyConflictRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import play.api.Configuration
import play.api.Logging
import value_objects.svo.KeyConflict

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import scala.collection.mutable.ListBuffer

// The durable local copy a detected key conflict is captured into before its relay
// notice is deleted, since the relay may lose its state at any time and must never be relied on
// to keep the alert alive.
@Singleton
class FileKeyConflictRepository @Inject() (config: Configuration) extends KeyConflictRepository, Logging:

  private val httpPort = config.getOptional[Int]("http.port").getOrElse(9000)
  private val file = File(s"./.devDBs/keyconflicts${httpPort}.ser")
  private var conflicts = ListBuffer.empty[KeyConflict]

  if file.exists then
    val ois = new ObjectInputStream(FileInputStream(file)):
      override def resolveClass(desc: java.io.ObjectStreamClass): Class[?] =
        try Class.forName(desc.getName, false, Thread.currentThread.getContextClassLoader)
        catch case _: ClassNotFoundException => super.resolveClass(desc)
    conflicts = ois.readObject().asInstanceOf[ListBuffer[KeyConflict]]
    ois.close()
  end if

  private def serializeConflicts(): Unit =
    val createdNewFile = file.createNewFile()
    if createdNewFile then logger.info(s"file $file created") else logger.info(s"file $file not created again")
    val oos = ObjectOutputStream(FileOutputStream(file))
    oos.writeObject(conflicts)
    oos.close()

  override def getAll(): List[KeyConflict] = conflicts.toList

  override def save(conflict: KeyConflict): Unit =
    val idx = conflicts.indexWhere(_.id == conflict.id)
    if idx >= 0 then conflicts.update(idx, conflict) else conflicts += conflict
    serializeConflicts()

  override def delete(id: UUID): Unit =
    conflicts.filterInPlace(_.id != id)
    serializeConflicts()
