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

import com.google.inject.AbstractModule
import driven_ports.ContactRepository
import driven_ports.ForgettableIdentityStore
import driven_ports.IdentityStore
import driven_ports.ShareMetadataRepository
import driven_ports.ShareRelay
import driven_ports.ShareRepository
import driving_ports.ContactManagement
import driving_ports.ForgettableIdentity
import driving_ports.Identity
import driving_ports.RequestSigner
import driving_ports.ShareManagement
import persistence.phon.FileContactRepository
import persistence.phon.FileIdentityStore
import persistence.phon.FileShareMetadataRepository
import persistence.phon.FileShareRepository
import persistence.phon.HttpClientShareRelay
import services.ContactService
import services.IdentityService
import services.ShareEncryption
import services.ShareService

class PhonModule extends AbstractModule:
  override def configure(): Unit =
    // when FileIdentityStore was still a Scala object: bind(classOf[IdentityStore]).toInstance(FileIdentityStore)
    bind(classOf[ForgettableIdentityStore]).to(classOf[FileIdentityStore])
    bind(classOf[ForgettableIdentity]).to(classOf[IdentityService])
    bind(classOf[RequestSigner]).to(classOf[IdentityService])
    bind(classOf[ShareEncryption]).to(classOf[IdentityService])
    bind(classOf[ContactRepository]).to(classOf[FileContactRepository])
    bind(classOf[ContactManagement]).to(classOf[ContactService])
    bind(classOf[ShareManagement]).to(classOf[ShareService])
    bind(classOf[ShareMetadataRepository]).to(classOf[FileShareMetadataRepository])
    bind(classOf[ShareRelay]).to(classOf[HttpClientShareRelay])
    bind(classOf[ShareRepository]).to(classOf[FileShareRepository])
