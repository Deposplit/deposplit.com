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

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import driven_ports.ForgettableIdentityStore
import driving_ports.ContactManagement
import driving_ports.ForgettableIdentity
import jakarta.inject.Inject
import jakarta.inject.Singleton
import play.api.Logging
import play.api.i18n.I18nSupport
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents
import play.api.mvc.Cookie
import play.api.mvc.DiscardingCookie
import play.api.mvc.Request

@Singleton
class PhonyPhoneController @Inject() (
    val controllerComponents: ControllerComponents,
    val identity: ForgettableIdentity,
    val contactManagement: ContactManagement
) extends BaseController,
      I18nSupport,
      Logging:

  val cookieNamePrefix = "PhonyPhone"

  def createPseudonym() = Action { implicit request: Request[AnyContent] =>
    logger.debug("creating pseudonym …")
    pseudonymForm
      .bindFromRequest()
      .fold(
        formWithErrors => {
          logger.debug("… failed to create pseudonym")
          BadRequest(views.html.Phon.registrationForm(formWithErrors))
        },
        pseudonym => {
          identity.register(pseudonym.pseudonym)
          logger.debug("… created pseudonym")
          Redirect(routes.PhonyPhoneController.readPseudonym())
            .flashing("success" -> "createdPseudonym")
        }
      )
  }

  def readPseudonym() = Action { implicit request: Request[AnyContent] =>
    if identity.isRegistered() then
      Ok(
        views.html.Phon
          .registration(
            QrPayload(identity.pseudonym(), identity.edPublicKey(), identity.xPublicKey()),
            contactManagement
          )
      )
    else Ok(views.html.Phon.registrationForm(pseudonymForm))
    end if
  }

  def deletePseudonym() = Action { implicit request: Request[AnyContent] =>
    identity.unregister()
    NoContent.withHeaders("HX-Redirect" -> routes.PhonyPhoneController.readPseudonym().absoluteURL())
  }

  def readQrCode() = Action { implicit request: Request[AnyContent] =>
    if !identity.isRegistered() then Conflict
    else
      val payload =
        QrPayload.encode(identity.pseudonym(), identity.edPublicKey(), identity.xPublicKey())
      val bitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256)
      val image = MatrixToImageWriter.toBufferedImage(bitMatrix)
      val baos = java.io.ByteArrayOutputStream()
      javax.imageio.ImageIO.write(image, "PNG", baos)
      val base64 = java.util.Base64.getEncoder.encodeToString(baos.toByteArray)
      Ok(s"""<img src="data:image/png;base64,$base64">""").as("text/html")
    end if
  }

  def createContact() = Action { implicit request: Request[AnyContent] =>
    logger.debug("creating contact …")
    contactForm
      .bindFromRequest()
      .fold(
        formWithErrors => {
          logger.debug("… failed to create contact")
          BadRequest(
            views.html.Phon.registration(
              QrPayload(identity.pseudonym(), identity.edPublicKey(), identity.xPublicKey()),
              contactManagement
            ) // FIXME
          )
        },
        contact => {
          contactManagement.addManually(contact.pseudonym, Array[Byte](), Array[Byte]()) // FIXME
          logger.debug("… created contact")
          Created
            .flashing("success" -> "createdContact")
        }
      )
  }

  def deletePhone(phoneId: Int) = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.Phon.deletedPhonyPhone(phoneId)).discardingCookies(DiscardingCookie(cookieNamePrefix + phoneId))
  }
