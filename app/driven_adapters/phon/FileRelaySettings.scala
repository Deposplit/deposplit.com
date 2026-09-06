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

import driven_ports.RelaySettings
import jakarta.inject.Inject
import jakarta.inject.Singleton
import play.api.Configuration
import play.api.Logging

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

// The fallback is this machine rather than api.deposplit.com, unlike the mobile apps': a phony phone exists to be
// pointed at a relay running beside it. The port a phone was started on is its own identity here, so two phones on one
// machine keep two settings files and can be aimed at two different relays.
@Singleton
class FileRelaySettings @Inject() (config: Configuration) extends RelaySettings, Logging:

  private val httpPort = config.getOptional[Int]("http.port").getOrElse(9000)
  private val Fallback = "http://localhost:9000"
  private val file = File(s"./.devDBs/relay${httpPort}.txt")
  private var configured: Option[String] = None

  if file.exists then
    val stored = Files.readString(file.toPath, StandardCharsets.UTF_8).strip()
    configured = Option.unless(stored.isEmpty)(stored)
  end if

  override def defaultRelayBaseUrl(): String = configured.getOrElse(Fallback)

  override def setDefaultRelayBaseUrl(url: Option[String]): Unit =
    configured = url.map(_.strip()).filter(_.nonEmpty)
    configured match
      case Some(value) =>
        Files.writeString(file.toPath, value, StandardCharsets.UTF_8)
        logger.info(s"default relay set to $value")
      case None =>
        val deleted = file.delete()
        if deleted then logger.info(s"file $file deleted") else logger.info(s"file $file was not there to delete")
