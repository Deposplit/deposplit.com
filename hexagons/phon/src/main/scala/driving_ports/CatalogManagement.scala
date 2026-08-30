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

package driving_ports

import value_objects.svo.Catalog

// Optional catalog export/import — a convenience backup of the *non-secret* catalog,
// never shares or private keys. phon has no UI for this (consistency, not parity) — the
// primitive exists for cross-platform consistency and is exercised via tests only.
trait CatalogManagement:
  def exportCatalog(): Catalog

  /** Merges contacts/secrets/shareMetadata from catalog into local storage — upsert-if-absent only, by id; an existing
    * local record is never overwritten by an imported one, since a stale backup could otherwise clobber more-current
    * local state. Returns the number of newly added contacts.
    */
  def importCatalog(catalog: Catalog): Int
