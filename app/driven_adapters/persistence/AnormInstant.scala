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

package driven_adapters.persistence

import anorm.Column
import anorm.TypeDoesNotMatch

import java.time.Instant

/** Reads a `TIMESTAMP WITH TIME ZONE` column as an `Instant`, for every repository here. Anorm's own `Column[Instant]`
  * knows the `java.sql.Timestamp` PostgreSQL returns but not the `OffsetDateTime` H2 returns.
  */
object AnormInstant:

  given Column[Instant] = Column.nonNull { (value, meta) =>
    value match
      case ts: java.sql.Timestamp        => Right(ts.toInstant)
      case odt: java.time.OffsetDateTime => Right(odt.toInstant)
      case d: java.util.Date             => Right(d.toInstant)
      case _                             =>
        Left(
          TypeDoesNotMatch(
            s"Cannot convert $value: ${value.getClass.getName} to Instant for column ${meta.column}"
          )
        )
  }
