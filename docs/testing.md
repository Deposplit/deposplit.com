# Manual end-to-end testing

The unit suites cover the domain thoroughly — `sbt test`, `./gradlew test` and
`swift test` between them exercise splitting, combining, signing, canonical byte
construction and every service rule. What they cannot cover is the part that only exists
when three devices, a real relay and a real network are involved: encryption across
platform boundaries, consent round-trips, and the social flows.

That is what this document is for. It replaces the near-duplicate flow lists that used to
live in both app READMEs.

## Start a relay

From `deposplit.com/`:

```bash
sbt -> h2-browser => run -Dconfig.file=conf/localhost.conf
```

It listens on port 9000 and uses a file-backed H2 database, so state survives restarts.

## Point a device at it

The relay URL is **not** a build-time constant on either platform. Both apps resolve their
default relay at runtime, and both let you change it from the in-app **Settings** screen.
`RelayDefaults` supplies a single fixed fallback (`https://api.deposplit.com`) when nothing
is configured.

| Target | Set the default relay to | Notes |
|---|---|---|
| Android emulator | `http://10.0.2.2:9000` | `10.0.2.2` is the emulator's alias for the host. Cleartext to that host is already allowed by `app/src/debug/res/xml/network_security_config.xml`. |
| Android device | `http://localhost:9000` | Through `adb reverse` — see [An Android phone](#an-android-phone). The `10.0.2.2` alias is emulator-only. |
| iOS Simulator | `http://localhost:9000` | The Simulator shares the host's network stack. |
| iOS device | `http://<the Mac's LAN IP>:9000` | Same Wi-Fi, and the IP rather than the Mac's `.local` name — see [An iPhone or iPad](#an-iphone-or-ipad). |

This is a one-time step per fresh install; the setting persists across restarts.

## An Android phone

A real phone reaches the relay through an `adb` tunnel rather than over Wi-Fi. That keeps the
phone's view of the relay at `localhost`, which the debug network security config already
permits cleartext to and `conf/localhost.conf` already lists in `play.filters.hosts.allowed`
— so there is no LAN IP to allow, no firewall rule to add, and nothing to change when DHCP
hands the computer a new address. The steps are the same on Windows and macOS; only the setup
around them differs.

**Before you start.** The phone needs Android 15 or newer (`minSdk` is 35) and a screen lock,
because Reconstruct goes through the biometric-or-device-credential prompt.

| | Windows | macOS |
|---|---|---|
| `adb` | `%LOCALAPPDATA%\Android\Sdk\platform-tools`, not on the PATH by default — add it under *Edit environment variables for your account*, or call `adb.exe` by its full path | `~/Library/Android/sdk/platform-tools`, not on the PATH by default — add `export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"` to `~/.zshrc` |
| USB driver | Pixels need the *Google USB Driver* (Android Studio → SDK Manager → SDK Tools); other makers ship their own, such as Samsung's. Without it the phone never shows up in `adb devices` | None needed |
| First connection | — | On Apple silicon, allow the phone when macOS asks whether to let the accessory connect |
| Gradle wrapper | `./gradlew` in Git Bash, `.\gradlew` in PowerShell | `./gradlew` |

1. **Enable USB debugging.** *Settings → About phone* (on Samsung, *→ Software information*),
   tap **Build number** seven times, then turn on *Settings → Developer options → USB
   debugging*. Connect the phone, accept the *Allow USB debugging?* prompt with *Always allow
   from this computer*, and check that `adb devices` lists it as `device`, not `unauthorized`.
   *Wireless debugging* in the same menu works too — pair with `adb pair` or Android Studio's
   *Pair devices using Wi-Fi* — and everything below applies unchanged.
2. **Start the relay** as described above.
3. **Open the tunnel.** `adb reverse tcp:9000 tcp:9000` makes the phone's `localhost:9000` the
   computer's port 9000. It lasts only as long as the connection, so run it again after every
   reconnect.
4. **Install the debug build.** Set `FAKE_PREMIUM=true` in `Android/local.properties` first:
   the relay field in the next step sits behind Premium, and `local.properties` is untracked,
   so every computer needs its own. Then, from `Android/`, `./gradlew installDebug` — or select
   the phone in Android Studio's device menu and press Run, which also gives you Logcat.
5. **Point the app at the relay.** Register, then set *Settings → Default relay* to
   `http://localhost:9000`.

A phone makes a good partner for phony phones: its camera can scan the QR code phon shows,
which exercises the real scanning path that phon itself cannot.

Wi-Fi instead of the tunnel is possible but more fragile: the LAN IP has to be added both to
the debug `network_security_config.xml` and to `play.filters.hosts.allowed`, the computer's
firewall has to let Java accept connections on port 9000 — on Windows, allow `java.exe` when
Windows Defender Firewall asks, and on macOS, allow `java` when asked or under *System Settings
→ Network → Firewall → Options* — and some networks isolate clients from one another
altogether.

## An iPhone or iPad

The app target covers both device families (`TARGETED_DEVICE_FAMILY = 1,2`), so on an iPad it
runs as an iPad app rather than as an enlarged iPhone one. Any iPhone or iPad on iOS or iPadOS
26.4 or newer will do — that is the deployment target — and it needs a Mac with Xcode. There is
no counterpart to `adb reverse`, so the device reaches the relay over Wi-Fi, by the Mac's LAN IP.

**Before you start.** The device needs a passcode, because Reconstruct asks for Face ID, Touch
ID or the passcode, and neither biometric can be set up without one. It must be on the same
Wi-Fi as the Mac. And it needs a team to be signed by. The app carries no entitlements, so a free
Personal Team is enough; the differences are in the housekeeping:

| | Personal Team (any Apple Account) | Apple Developer Program team |
|---|---|---|
| Bundle ID | `com.deposplit.Deposplit` may already belong to another team. If Xcode says it is not available, append something of your own for local runs | Registered to the team on the first run, if it is not already |
| Profile | Expires after 7 days; run from Xcode again to renew it | Lasts a year |
| First launch | Refused until you trust the developer under *Settings → General → VPN & Device Management* | Runs straight away |

1. **Choose the team.** Add the account under *Xcode → Settings → Apple Accounts*. In
   `iOS/Deposplit.xcodeproj`, select the **Deposplit** target and pick the team under
   *Signing & Capabilities*; *Automatically manage signing* is already on. This writes
   `DEVELOPMENT_TEAM` into `project.pbxproj`, which is tracked and deliberately carries none —
   like the scheme edit for `skipBiometric`, it is for running, not committing. So is a changed
   bundle ID.
2. **Pair the device.** Connect it by cable, unlock it, and tap *Trust* when it asks about the
   computer; on Apple silicon, also allow the accessory when macOS asks. Choose *Manage
   Devices…* from the run destination menu to open Device Hub, select the device, and click
   *Pair* if it offers one. The first connection can take a few minutes while Xcode prepares
   the device. Wireless pairing needs iOS or iPadOS 27, so an older device pairs by cable —
   but once paired, it runs over Wi-Fi as well, provided the network has IPv6. After an OS
   update, pair it again.
3. **Turn on Developer Mode.** *Settings → Privacy & Security → Developer Mode*. The switch only
   appears once pairing has begun. The device restarts, then asks once more; confirm with the
   passcode.
4. **Let the device in, then start the relay.** Add the Mac's LAN IP — `ipconfig getifaddr en0`
   on most Macs — to `play.filters.hosts.allowed` in `conf/localhost.conf`, then start the relay
   as described above. That edit is for running, not committing, too. If the macOS firewall is
   on, allow `java` to accept incoming connections when asked, or under *System Settings →
   Network → Firewall → Options*.
5. **Run.** Choose the device as the run destination and press Run. With a Personal Team, the
   first launch fails until you trust the developer as above; then press Run again.
6. **Point the app at the relay.** Register. *Settings → Default relay* sits behind Premium, so
   buy it on the paywall first: a run from Xcode uses the scheme's `Deposplit.storekit` on a
   device just as it does in the Simulator, so nothing is charged and no App Store Connect record
   is involved. Then set the default relay to `http://<the Mac's LAN IP>:9000`. The first request
   raises the *Local Network* alert — allow it, then refresh, because the request that raised it
   may already have failed.

**Why the IP and not the Mac's name.** App Transport Security does not apply to IP addresses, so
plain HTTP to one needs no exception. `http://<name>.local:9000` would be refused: that needs
`NSAllowsLocalNetworking`, which the app does not carry. The price is the one the Android tunnel
avoids — when DHCP hands the Mac a new address, both `play.filters.hosts.allowed` and the
setting in the app need it too. Some networks also isolate clients from one another altogether.

**The Local Network permission.** The Mac's IP is a local network address, so every connection
to it needs the device's *Local Network* permission. The Simulator has no such permission, which
is why none of this shows up there. If it was denied, the relay simply looks unreachable; turn it
back on under *Settings → Privacy & Security → Local Network*. A background pass that finds the
permission still undecided is denied without an alert, so grant it in the foreground before
relying on [Background refresh](#background-refresh).

**A relay on a Windows PC.** The relay need not run on the Mac, which still builds and installs
the app. On a PC, the PC's IP — from `ipconfig` — takes the Mac's place in
`play.filters.hosts.allowed` and in the app, and Windows Defender Firewall is the extra hurdle.
Nothing else has had to reach the PC before, because the Android phone arrives through its
tunnel, so several things can stand in the way:

- Windows may class the network as *Public*. Make it *Private* under *Settings → Network &
  internet →* the connection *→ Network profile type*.
- The firewall asked about `java.exe` the first time the relay started. Answering *Cancel* leaves
  inbound **block** rules behind, and so does any answer from an account without administrator
  rights. A block rule beats every allow rule, so adding one later changes nothing until the
  block is gone. Allowing private networks only, while the network was classed public, leaves it
  blocked too.
- A rule for `java.exe` names one full path, so a JDK update quietly leaves it behind.

With the network set to *Private*, removing any such block rules and allowing the port rather
than the program settles the other two. In an administrator PowerShell:

```powershell
# What Windows has already decided for java.exe. Look before removing anything.
Get-NetFirewallApplicationFilter | Where-Object Program -like '*java.exe' |
  Get-NetFirewallRule | Format-Table DisplayName, Action, Profile, Enabled

# Remove the block rules among them.
Get-NetFirewallApplicationFilter | Where-Object Program -like '*java.exe' |
  Get-NetFirewallRule | Where-Object Action -eq Block | Remove-NetFirewallRule

# Allow the relay's port, whichever JDK serves it: private networks, local subnet only.
New-NetFirewallRule -DisplayName 'Deposplit dev relay' -Direction Inbound -Protocol TCP `
  -LocalPort 9000 -RemoteAddress LocalSubnet -Profile Private -Action Allow
```

Test from the Mac before involving the device: `curl -i http://<the PC's IP>:9000/`. Any HTTP
reply, even a 400 because the host is not allowed yet, means the firewall lets it through and only
`play.filters.hosts.allowed` is left to fix. A hang means the firewall, or a network that isolates
its clients. If the Mac gets through and the device does not, look at its Local Network
permission. All of this holds for an Android phone that uses Wi-Fi instead of its tunnel, too.

What a real device adds over the Simulator:

- **The camera.** Scanning runs for real, so scan the QR code phon shows on the Mac's screen.
- **Touch ID.** An iPad without Face ID — every iPad mini, for one — authenticates with Touch
  ID, so the *Authentication availability* edge case is about the finger rather than the face.
- **iPad layout.** All four orientations and resizable windows. Check each tab in landscape and
  in a narrow window, and that sheets such as the scanner and the paywall present sensibly.
- **A locked device.** The last check under [Background refresh](#background-refresh) is only
  answerable here. The `plutil` path there reads `Debug-iphoneos` for a device build, not
  `Debug-iphonesimulator`.

An Android phone can share the relay at the same time — the phone at `localhost` through its
tunnel, this device at the Mac's IP. That is Flow 6 on real hardware, with each camera scanning
the other's QR code.

## Three devices

The full social flow needs **three instances** — Alice plus two holders. Three emulators,
three simulators, phony phones, or a mix.

- *Android*: create two extra AVDs (API 35+, matching `minSdk`) in the Device Manager and
  launch them alongside the first.
- *iOS*: Xcode runs one simulator from the Run button, but you can open more via
  **Xcode → Open Developer Tool → Device Hub**, then **File → New Simulator**.
- *phon*: one phone per running instance, each with its own stores, named after the port it
  was started on. The relay is the instance on 9000, so start the extra phones beside it:

  ```bash
  sbt -> h2-browser => run -Dconfig.file=conf/localhost.conf                 # relay + Alice, port 9000
  sbt startServer "run -Dconfig.file=conf/phon.conf -Dhttp.port=9001"        # Bob
  sbt startServer "run -Dconfig.file=conf/phon.conf -Dhttp.port=9002"        # Carol
  ```

  Each answers at `http://localhost:<port>/phonyPhone`. Bob's and Carol's own port is not a
  relay, so point their **Settings → Default relay** at `http://localhost:9000`.
  **Settings → Danger zone** wipes a phone back to a clean slate between runs.

Mixing platforms is not just possible but the most valuable configuration — see Flow 6.

**Exchanging contact details without a camera.** A phony phone cannot scan. Open **My
contact details** from the top bar, copy the payload, and paste it into the other phone's
**Contacts → ＋ → Paste details** — that goes through the same `addFromQr` path a scan does,
including the verification level it earns. Typing the two keys by hand is the other way in,
and stops one level short on purpose. A real handset can still scan the QR code phon shows.

## Flow 1 — Happy path, 2-of-2 across two holders

| Step | Device | Action |
|---|---|---|
| 1 | A | Launch, register as "Alice" |
| 2 | B | Launch, register as "Bob" |
| 3 | C | Launch, register as "Carol" |
| 4 | A | QR icon in the top bar → Alice's QR appears |
| 5 | B | Contacts → add contact → enter Alice's keys manually (or scan); then show Bob's QR |
| 6 | C | Same — add Alice, then show Carol's QR |
| 7 | A | Add Bob and Carol as contacts |
| 8 | A | ＋ → label ("test secret"), secret text, select Bob and Carol, threshold 2-of-2 → **Deposit** |
| 9 | A | Split & shared view shows one card for the secret; tap it to open the secret's own screen with both holders |
| 10 | B | Keeping safe view shows Alice's deposit → the app auto-approves, decrypts, and stores the **plaintext** share; the relay clears the ciphertext |
| 11 | C | Same |
| 12 | A | On the secret's screen, **Request Retrieval** (opens retrievals for both holders at once) |
| 13 | B | Requests → retrieval from Alice → **Approve**. The app re-encrypts the stored plaintext to Alice's *current* key |
| 14 | C | Same |
| 15 | A | Both holders show "Approved" → **Reconstruct** → biometric prompt → the secret appears |
| 16 | A | **Clear collected copies** → confirm. The secret disappears from the screen, Reconstruct goes quiet again, and **Request Retrieval** comes back |
| 17 | A | Run steps 12–15 a second time. Everything works with nothing touched in the relay database |

Step 10 is the one worth watching closely: it is where holder-decrypts-at-pickup happens,
and where the relay stops holding anything.

Steps 16 and 17 are the pair that proves clearing is not teardown. After 16, check the relay's
`share_requests`: the secret's retrieval rows are gone, its deposit rows are not, and Bob's and
Carol's Keeping safe lists are untouched. Check too that every disabled control on the secret's
screen says why it is disabled — that is the rule the screen exists to keep.

## Flow 2 — Deny and re-request

At step 13, Bob taps **Deny** instead. Alice's view shows "Denied" with a retry affordance;
she re-requests and Bob approves.

## Flow 3 — Sender-initiated removal

Alice opens a **Removal** request on one share. Bob's Requests tab shows it; Bob approves;
Bob's deposit row is deleted, cascading to any related retrieval and removal rows, and the
share disappears from Bob's Keeping safe view.

## Flow 4 — Holder-initiated deletion

Bob deletes Alice's share from his Keeping safe view directly — the trash icon on the row, or a
swipe on iOS, which opens the same confirmation — with no request and no approval. If Bob holds
several shares from Alice, the confirmation also offers to delete all of them. Then check what
Alice sees on refresh: she should learn about it eventually, but never by a row simply going
missing.

Before Alice refreshes, have her ask for the share back. Her phone has not seen the deletion yet,
so the ask reaches Bob, whose Requests tab must show it with Approve disabled and the reason
beneath it — there is nothing left to hand back — while Deny still works. Once Alice refreshes,
her phone drops Bob as a holder and the ask disappears from Bob's list on his next refresh.

## Flow 5 — Offline and error states

Kill the relay, then open or refresh both apps.

- Split & shared and Keeping safe views must still render **from local storage**, with a soft
  warning rather than a blocking error: *Relay 10.0.2.2:9000 not reachable. Showing the last
  known state.* — naming whichever host and port that device uses.
- The Requests tab queries the relay for pending events that are not stored locally, so with
  its only relay down it has nothing to show but a warning naming that relay — and no *No
  pending requests*, which it cannot know. That is correct behaviour.

Restart the relay, navigate away and back — the warning clears and data refreshes. Flow 7 covers
the case with two relays, where only one goes dark.

## Flow 6 — Cross-platform

Run Alice on iOS and Bob on Android at the same time, against one relay. Alice deposits for
Bob; Bob sees it in Keeping safe; Bob opens a retrieval; Alice reconstructs.

This is the highest-value flow in this document, because it is the only test that proves
CryptoKit and BouncyCastle produce interoperable X25519 + HKDF-SHA-256 +
ChaCha20-Poly1305 bytes on a live wire. The vector tests prove the canonical byte
constructions agree; this proves the whole stack does.

## Flow 7 — Bring Your Own Relay

Run **two** relays on different ports:

```bash
sbt -> h2-browser => run -Dconfig.file=conf/localhost.conf                   # port 9000
sbt startServer "run -Dconfig.file=conf/phon.conf -Dhttp.port=9001"          # port 9001
```

The per-contact override is free everywhere, so it needs no unlock. Only **Settings → Default
relay** sits behind Premium: on iOS buy it in the Simulator or on a device run from Xcode (the
scheme carries `Deposplit.storekit`, so no App Store Connect record is needed); on Android set
`FAKE_PREMIUM=true` in `local.properties` and rebuild, since Play Billing cannot run without a
Play Console listing. phon has no purchases at all, so both are simply editable there.

Give one contact a `relayBaseUrl` override pointing at 9001 and leave another with no
override. Then verify that deposit, pickup, retrieval and removal all route through the
override for the first contact while the second still round-trips through the default —
and that killing one relay degrades only that contact, leaving the other's operations
working. The fan-out is independently soft-failed per relay precisely so that holds.

With the 9001 relay killed, refresh: Split & shared and Keeping safe show exactly one warning,
naming `localhost:9001` (or whatever host this device reaches it by), and none for the default. The
Requests tab still lists what the default relay holds, with one line saying the 9001 relay's
requests cannot be shown right now.

## Flow 8 — Locale

Switch the device to German and relaunch. All strings should appear in German, with dates
in `dd.MM.yyyy`. On Android: **Settings → General management → Language**.

## Edge cases worth checking

- **Fresh keypairs after reinstall.** Clearing app data or reinstalling generates new keys;
  existing contacts can no longer decrypt shares sent to the old ones.
- **Reconstruct stays disabled below threshold, and says how far short it is.** The button is
  always on screen; what changes is whether it can be pressed and what the line beneath it reads.
- **Request Retrieval stays enabled at *k*.** With three holders and a 2-of-3 threshold, two
  approvals must not silence the third ask — the surplus is what the integrity cross-check needs.
  It goes quiet only once every holder has a pending or approved retrieval.
- **2-of-3 with only two approvals** still reconstructs.
- **Integrity margin.** With three holders and a 2-of-2 threshold, all three approving gives
  a margin of one — enough to *detect* a bad share. Confirm the reconstruction advisory
  reports the margin honestly rather than claiming more confidence than it has.
- **Verification levels.** Manual key entry defaults to `VERY_LOW` and offers `LOW`/`HIGH`
  but never `VERY_HIGH`; QR scan defaults to `VERY_HIGH`.
- **Authentication availability, not API level.** Every phone Deposplit installs on takes the
  device passcode as well as the face or finger — Android because `minSdk` is 35 and the
  combined `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` authenticator is only missing below API 30,
  iOS because `.deviceOwnerAuthentication` has always had it. Check both halves: turning Face
  ID off, or failing the face three times, must still reach the passcode and then the secret.
  What still varies is the *device*: an emulator or Simulator with nothing set up at all must
  explain itself rather than offer a button that cannot work. That is also the state
  `skipBiometric` exists for on the Simulator — see `iOS/CLAUDE.md`.
- **Key-change indicator.** After a contact rotates keys, their retrieval requests should
  carry the "key changed N days ago" warning — and only retrieval requests.

## Background refresh

Custody no longer waits for somebody to open the app: a daily pass runs `syncInbox()` on both
phones, so heartbeats and pickups happen on their own, and a waiting retrieval raises one local
notification. None of that is reachable by a unit test — it is adapter code by definition — so it
is checked here or nowhere.

**Firing a pass by hand, Android.** WorkManager runs on JobScheduler underneath, so:

```bash
adb shell dumpsys jobscheduler | grep -A 3 com.deposplit   # find the job id
adb shell cmd jobscheduler run -f com.deposplit <id>       # -f ignores the constraints
```

**Firing a pass by hand, iOS.** A pass never fires on its own in the Simulator, and on a device it
fires when iOS decides to. Run from Xcode, send the app to the background, then pause the debugger:

```
(lldb) e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.deposplit.custody-refresh"]
```

Before trusting any of it, confirm the two keys actually reached the built app — the failure is
silent, and this is the only thing that catches it:

```bash
plutil -p <DerivedData>/Build/Products/Debug-iphonesimulator/Deposplit.app/Info.plist \
  | grep -E -A 3 'BGTaskSchedulerPermittedIdentifiers|UIBackgroundModes'
```

Force-stop the app first for the checks that claim the app was never opened, and confirm from the
*sender's* device rather than the holder's — the point is what the owner can see.

- **A pass emits without a launch.** Hold a share, force-stop the holder, fire a pass, then check
  the sender: `lastConfirmedAt` moves and the holder stays Confirmed.
- **Silence past the threshold no longer costs anything.** Move the clock past nine days with the
  holder's app never opened. The owner must still read Confirmed rather than Silent / overdue.
- **A waiting retrieval notifies exactly once.** Request a retrieval, fire two passes. One
  notification, not two — and read it on the lock screen: it must name nobody, name no secret, and
  not say how many are waiting. Also confirm the sentence is not truncated.
- **Answering clears it.** Approve the request, fire a pass, and confirm nothing is re-announced.
- **A removal is silent.** Request a removal with no retrieval outstanding, fire a pass, and
  confirm nothing at all is posted. It is still in the Requests tab.
- **The permission is asked at the right moment.** A fresh install that holds nothing must never
  see the prompt; it appears on the launch where the first share lands, and never again. Deny it,
  then check Settings: the line says notifications are off and offers the way to the system page.
  On iOS the explanation comes first — take *Not now*, then check Settings, where the line must say
  it is not turned on yet and the button must raise the system prompt. Deny that, and the line and
  the button must change to the system page. Turn it on there, come back, and the line must have
  followed.
- **A locked iPhone sits the pass out.** Fire a pass with the phone locked. Nothing may be emitted
  and nothing may be written — key storage is `WhenUnlockedThisDeviceOnly`, so a pass that tried
  would be signing with nothing. Only a device can answer this; the Simulator's Keychain does not
  behave like a locked phone's.
