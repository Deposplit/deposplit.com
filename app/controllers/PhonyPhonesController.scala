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

package controllers

import play.api.i18n.I18nSupport
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents
import play.api.mvc.Cookie
import play.api.mvc.DiscardingCookie
import play.api.mvc.Request

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhonyPhonesController @Inject() (val controllerComponents: ControllerComponents)
    extends BaseController
    with I18nSupport:

  val cookieNamePrefix = "PhonyPhone"

  def readPhones() = Action { implicit request: Request[AnyContent] =>
    val phonyPhones = request.cookies
      .filter(_.name.startsWith(cookieNamePrefix))
      .map(cookie => PhonyPhone(cookie.name.substring(cookieNamePrefix.length).toInt, cookie.value))
      .toSeq
    Ok(views.html.phonyPhones(phonyPhones))
  }

  def readPhone(phoneId: Int) = Action { implicit request: Request[AnyContent] =>
    val phonyPhone = request.cookies
      .find(_.name == cookieNamePrefix + phoneId)
      .map(cookie => PhonyPhone(phoneId, cookie.value))
    if (phonyPhone.isDefined) {
      Ok(views.html.phonyPhone(phonyPhone.get))
    } else {
      NotFound
    }
  }

  def updatePseudonym(phoneId: Int) = Action { implicit request: Request[AnyContent] =>
    NoContent
  }

  def createPhone() = Action { implicit request: Request[AnyContent] =>
    val cookieNames = request.cookies.map(_.name).toSet
    var phoneId = 1
    while cookieNames.contains(cookieNamePrefix + phoneId) do phoneId += 1
    Created.withCookies(Cookie(cookieNamePrefix + phoneId, "pseudonym"))
  }

  def deletePhone(phoneId: Int) = Action { implicit request: Request[AnyContent] =>
    NoContent.discardingCookies(DiscardingCookie(cookieNamePrefix + phoneId))
  }
