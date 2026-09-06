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
import play.api.i18n.I18nSupport
import play.api.i18n.Messages
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.Call
import play.api.mvc.Request
import play.api.mvc.RequestHeader
import play.api.mvc.Result
import play.twirl.api.Html

/** What every phon controller shares: the registration gate, the fragment-or-page decision, and the shell.
  *
  * The fragment-or-page decision is the one that used to be missing. Screens were reachable only as bare `<div>`s, so
  * visiting one directly gave a browser a document with no `<html>` around it, while the not-registered branch of the
  * same action returned a whole page into an htmx target. Deciding it from `HX-Request` in one place means every screen
  * is both swappable and bookmarkable, and neither case has to be remembered per action.
  */
trait PhonSupport extends BaseController, I18nSupport:

  protected def identity: ForgettableIdentity
  protected def contactManagement: ContactManagement
  protected def shareManagement: ShareManagement
  protected def relaySettings: RelaySettings

  protected def isHtmx(using request: RequestHeader): Boolean =
    request.headers.get("HX-Request").isDefined

  /** Renders `body` inside the shell — as the `#screen` fragment for htmx, as a whole document otherwise. */
  protected def render(shell: Shell, body: Html, status: Status = Ok)(using
      request: RequestHeader,
      messages: Messages
  ): Result =
    status(if isHtmx then views.html.Phon.screen(shell, body) else views.html.Phon.phonyPhone(shell, body))

  /** Go to another screen after acting on this one.
    *
    * Never a plain redirect for an htmx request: htmx would follow it, receive a whole document and swap that document
    * into `#screen`, nesting a page inside a page. `HX-Location` is the htmx-shaped answer — it asks the client to
    * fetch the new screen and swap it where the navigation links do, so the URL bar and the history entry come out the
    * same as if the user had tapped through.
    */
  protected def goTo(call: Call)(using request: RequestHeader): Result =
    if isHtmx then
      NoContent.withHeaders(
        "HX-Location" -> s"""{"path":"${call.url}","target":"#screen","swap":"outerHTML"}"""
      )
    else Redirect(call)

  /** Runs `screen` only on a registered device, and otherwise renders the sign-in gate — the same three-way start
    * decision `RootView` and `MainActivity` make, minus the keys-lost branch phon cannot reach (`IdentityIntegrity`
    * explains why: its identity store keeps keys and state in one file, so they cannot come apart).
    */
  protected def registered(
      screen: => Result
  )(using request: Request[AnyContent], messages: Messages): Result =
    if identity.isRegistered() then screen else Ok(views.html.Phon.signIn(PhonForms.pseudonymForm))

  protected def shellFor(
      titleKey: String,
      tab: Option[PhonTab] = None,
      back: Option[play.api.mvc.Call] = None,
      syncWarning: Boolean = false
  ): Shell =
    Shell(
      titleKey = titleKey,
      pseudonym = identity.pseudonym(),
      tab = tab,
      back = back,
      awaitingRelinkCount = contactManagement.contactsAwaitingRelink().size,
      syncWarning = syncWarning
    )
