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

import play.api.i18n.Messages

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A date as the reader's own locale writes it - dd.MM.yyyy in German, an abbreviated month in English.
  *
  * Every one of these used to be `instant.toString.take(10)`, which is the ISO prefix an `Instant` happens to start
  * with: the same nine characters to a German reader as to an English one, and a format neither app shows. Android
  * reaches the same two shapes through `ofLocalizedDate(FormatStyle.MEDIUM)` and iOS through
  * `formatted(date: .abbreviated)`.
  *
  * The locale comes from the request rather than the JVM, which is the one way this differs from Android: a phony phone
  * serves both languages at once, so there is no device setting to read. The zone is the JVM's own, because a phony
  * phone runs on a developer's machine and has no user profile to ask.
  */
object PhonDates:

  private val medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

  def format(at: Instant)(using messages: Messages): String =
    medium.withLocale(messages.lang.toLocale).format(at.atZone(ZoneId.systemDefault()))
