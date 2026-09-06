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

import org.scalatestplus.play.*
import play.api.Configuration

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The default relay is the one setting a phony phone keeps outside its identity, and it has to survive a restart: the
  * whole point of Flow 7 is aiming two phones at two relays and leaving them there.
  *
  * Every case here uses a port of its own, because the port is what names the file — the same mechanism that lets two
  * phones share a machine without sharing state.
  */
class FileRelaySettingsSpec extends PlaySpec {

  private def onPort(port: Int)(check: FileRelaySettings => Unit): Unit =
    val file = File(s"./.devDBs/relay$port.txt")
    File("./.devDBs").mkdirs()
    file.delete()
    try check(FileRelaySettings(Configuration("http.port" -> port)))
    finally file.delete()

  "FileRelaySettings" should {

    "fall back to the relay running beside it when nothing is configured" in {
      onPort(19001)(_.defaultRelayBaseUrl() mustBe "http://localhost:9000")
    }

    "report the URL it was given" in {
      onPort(19002) { settings =>
        settings.setDefaultRelayBaseUrl(Some("http://localhost:9001"))
        settings.defaultRelayBaseUrl() mustBe "http://localhost:9001"
      }
    }

    "still report it after a restart" in {
      onPort(19003) { settings =>
        settings.setDefaultRelayBaseUrl(Some("http://10.0.2.2:9000"))
        FileRelaySettings(Configuration("http.port" -> 19003)).defaultRelayBaseUrl() mustBe "http://10.0.2.2:9000"
      }
    }

    "treat a blank URL as no URL, so a cleared field resets rather than breaking every request" in {
      onPort(19004) { settings =>
        settings.setDefaultRelayBaseUrl(Some("http://localhost:9001"))
        settings.setDefaultRelayBaseUrl(Some("   "))
        settings.defaultRelayBaseUrl() mustBe "http://localhost:9000"
      }
    }

    "reset to the fallback when given None" in {
      onPort(19005) { settings =>
        settings.setDefaultRelayBaseUrl(Some("http://localhost:9001"))
        settings.setDefaultRelayBaseUrl(None)
        settings.defaultRelayBaseUrl() mustBe "http://localhost:9000"
        FileRelaySettings(Configuration("http.port" -> 19005)).defaultRelayBaseUrl() mustBe "http://localhost:9000"
      }
    }

    "keep two phones on one machine apart" in {
      onPort(19006) { alice =>
        onPort(19007) { bob =>
          alice.setDefaultRelayBaseUrl(Some("http://localhost:9000"))
          bob.setDefaultRelayBaseUrl(Some("http://localhost:9001"))
          alice.defaultRelayBaseUrl() mustBe "http://localhost:9000"
          bob.defaultRelayBaseUrl() mustBe "http://localhost:9001"
        }
      }
    }
  }
}
