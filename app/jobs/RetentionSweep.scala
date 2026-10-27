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

package jobs

import driving_ports.CustodyHeartbeats
import driving_ports.KeyRotations
import jakarta.inject.*
import play.api.Configuration
import play.api.Logging
import play.api.inject.ApplicationLifecycle

import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.util.control.NonFatal

/** Expires relay rows whose addressee never came for them. Every other row is deleted by the app it is addressed to
  * once that app has acted on it, so this is a safety net for the rows no app is left to delete — see "Absence is never
  * a signal" in docs/protocol.md. Each table's retention period is set in application.conf under `relay.retention`.
  *
  * A driving adapter like the controllers, only driven by a clock instead of a request, and synchronous like everything
  * else: one daemon thread, one DELETE per table per sweep. Several instances each running it is harmless, because a
  * second sweep finds nothing left to expire.
  */
@Singleton
class RetentionSweep @Inject() (
    config: Configuration,
    lifecycle: ApplicationLifecycle,
    heartbeats: CustodyHeartbeats,
    rotations: KeyRotations
) extends Logging:

  private val custodyHeartbeatsRetention: Duration = config.get[Duration]("relay.retention.custody-heartbeats")
  private val keyRotationsRetention: Duration = config.get[Duration]("relay.retention.key-rotations")

  /** One sweep, now. Never throws: a task that throws cancels every later run of a `ScheduledExecutorService`, and does
    * so silently. Each table is swept on its own, so a failure on one does not spare the rest.
    */
  def sweep(): Unit =
    val now = Instant.now()
    expire("custody_heartbeats")(heartbeats.expireHeartbeats(now.minus(custodyHeartbeatsRetention)))
    expire("key_rotations")(rotations.expireRotations(now.minus(keyRotationsRetention)))

  private def expire(table: String)(expiry: => Int): Unit =
    try expiry
    catch case NonFatal(e) => logger.warn(s"Relay retention sweep failed for $table", e)

  if config.get[Boolean]("relay.retention.enabled") then
    val interval = config.get[Duration]("relay.retention.sweep-interval")
    val executor = Executors.newSingleThreadScheduledExecutor { runnable =>
      val thread = Thread(runnable, "relay-retention-sweep")
      thread.setDaemon(true)
      thread
    }
    // The first sweep waits a minute, so that it never races the evolutions of a relay that is still starting up.
    executor.scheduleWithFixedDelay(
      () => sweep(),
      Duration.ofMinutes(1).toMillis,
      interval.toMillis,
      TimeUnit.MILLISECONDS
    )
    lifecycle.addStopHook(() => Future.successful(executor.shutdownNow()))
