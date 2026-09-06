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

package controllers.phon

import driven_ports.RelaySettings
import driving_ports.ContactManagement
import driving_ports.ForgettableIdentity
import driving_ports.ShareManagement
import jakarta.inject.Inject
import jakarta.inject.Singleton
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.Request

import java.util.UUID
import scala.util.Try

/** Shares this device holds for other people.
  *
  * Both actions here are unilateral by design: a holder may always stop holding, with no request and nobody's approval.
  * What the owner is owed is not a veto but the news, which the relay tombstone carries on her next poll — she must
  * never learn of it by a row simply going missing.
  */
@Singleton
class HeldController @Inject() (
    val controllerComponents: ControllerComponents,
    override protected val identity: ForgettableIdentity,
    override protected val contactManagement: ContactManagement,
    override protected val shareManagement: ShareManagement,
    override protected val relaySettings: RelaySettings
) extends PhonSupport:

  def delete(shareId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      Try(shareManagement.deleteHeldShare(shareId))
      goTo(routes.HomeController.held())
    }
  }

  def deleteAllFromSender(contactId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      Try(shareManagement.deleteAllHeldFromSender(contactId))
      goTo(routes.HomeController.held())
    }
  }
