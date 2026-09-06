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

import play.api.mvc.Call

/** The bottom bar. Three tabs, because that is what both apps have: iOS's `HomeView` is a `TabView` of Distributed /
  * Held / Requests and Android's `HomeScreen` a `SecondaryTabRow` of the same three. Contacts, the QR code and Settings
  * are reached from the top bar on both platforms, so they are not tabs here either.
  */
enum PhonTab(val titleKey: String, val icon: String):
  case Distributed extends PhonTab("phon.tab.distributed", "bi-arrow-up-circle")
  case Held extends PhonTab("phon.tab.held", "bi-inbox")
  case Requests extends PhonTab("phon.tab.requests", "bi-bell")

  def call: Call = this match
    case Distributed => routes.HomeController.distributed()
    case Held        => routes.HomeController.held()
    case Requests    => routes.HomeController.requests()

/** Everything the chrome around a screen needs. Rendered as one `#screen` element so a tab switch swaps the top bar,
  * the advisories and the tab bar together, rather than leaving a stale title above fresh content.
  */
final case class Shell(
    titleKey: String,
    pseudonym: String,
    /** `Some` on the three tabs, `None` on a pushed screen — which is also what decides whether the bottom bar shows at
      * all, matching a phone where a pushed screen covers it.
      */
    tab: Option[PhonTab] = None,
    /** `Some` turns the top bar's icons into a back arrow, as on every pushed screen in both apps. */
    back: Option[Call] = None,
    /** Contacts who still hold a key this device no longer signs with. A standing advisory, not an alarm: it is
      * expected work after a phone switch and clears itself as each contact gets back in touch.
      */
    awaitingRelinkCount: Int = 0,
    /** The relay could not be reached on the last attempt. Soft — the local lists still render. */
    syncWarning: Boolean = false
):
  def isTab: Boolean = tab.isDefined
