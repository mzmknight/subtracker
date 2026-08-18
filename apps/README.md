# SubTracker apps

Compose Multiplatform. One shared module holds the models, the API client and **all** the UI;
the two app modules are thin entry points.

```
shared/
  core/    PlainDate, Money — the same plain-date and integer-minor-unit discipline
           the backend enforces, with tests asserting the same answers
  data/    wire models, Ktor client, settings storage
  ui/      every Compose screen: dashboard, list, detail, edit, connect
androidApp/   Activity + SharedPreferences store
desktopApp/   main() + java.util.prefs store + jpackage config
```

## Build and run

Gradle needs a JDK; Android Studio's bundled one works:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

Run the shared tests:

```bash
./gradlew :shared:desktopTest
```

Run the Windows app:

```bash
./gradlew :desktopApp:run
```

Build the Android debug APK:

```bash
./gradlew :androidApp:assembleDebug
```

Package the Windows installer:

```bash
./gradlew :desktopApp:packageMsi
```

Build the portable version (no install, nothing left behind):

```bash
./gradlew :desktopApp:packagePortable
```

## Portable vs installed

The same binary behaves either way, decided by whether `portable.txt` sits next to
`SubTracker.exe`:

| | Database and settings |
|---|---|
| **Portable** (zip) | `data\` beside the executable — copy the folder to a USB stick and your subscriptions come with it |
| **Installed** (MSI) | `%LOCALAPPDATA%\SubTracker` |

The portable build titles its window "SubTracker (portable)" so you can tell which copy you are
looking at, and mints its own device identity — so a portable copy and an installed copy on the
same machine are two distinct sync peers, not one confused one.

Settings are a properties file in the data directory rather than `java.util.prefs`, which writes
to `HKCU`. A portable build must not touch the registry: leaving a device identity and server
credentials behind on someone else's machine is exactly what portable is supposed to avoid.
(That also sidesteps the 8 KB per-value limit `java.util.prefs` imposes.)

Two things about packaging that cost a debugging cycle each:

**Use a full JDK, not the JetBrains Runtime.** Android Studio's bundled `jbr` has no `jpackage`,
so `JAVA_HOME` must point at a real JDK (`C:\Program Files\Java\jdk-25.0.2` here) for packaging
tasks. WiX is fetched automatically by the Compose plugin — no manual install needed.

**`modules("java.sql", ...)` is load-bearing.** jpackage builds a minimised runtime with jlink,
which leaves out `java.sql` — and SQLDelight's JDBC driver needs `DriverManager`. Without it the
packaged app launches, throws `NoClassDefFoundError` during the first composition, and dies with
no window and no message. It only fails when packaged: running through Gradle uses the full JDK
and works fine, so nothing in the normal build catches it.

That silent death is also why `main()` writes to `%LOCALAPPDATA%\SubTracker\startup-error.log`.
jpackage produces a windowed executable with no console, so an exception during startup otherwise
vanishes completely. If the app ever fails to appear, that file says why.

## Local-first

The device owns the data. Every read and every write goes to a local SQLite database, and the
billing projection runs on-device — so the app is fully usable with no server in existence, not
merely degraded without one.

| | Location |
|---|---|
| Android | app-private `subtracker.db` — removed on uninstall |
| Windows | `%LOCALAPPDATA%\SubTracker\subtracker.db` |

Only *intent* is stored and synced: subscriptions, price history, and charge overrides. Charges
are derived and recomputed on demand, which is why a full sync payload is a few KB regardless of
how many years of history exist.

Three invariants hold this together, and breaking any of them breaks sync:

- **`updated_at` is a hybrid logical clock**, not wall time. Devices disagree about the time by
  seconds or worse, so a plain timestamp lets a later edit lose. Encoded fixed-width so
  lexicographic order is causal order.
- **Deletes are tombstones.** A removed row and a never-seen row are indistinguishable during a
  merge, so a hard delete gets resurrected by a peer's stale copy.
- **Charge-override ids are derived** from `(subscription, due_date)`, not random. Two devices
  skipping the same charge must produce the *same* record for last-write-wins to settle it.

The engine exists twice — JS on the server, Kotlin on devices — and the two are checked against
a shared generated corpus (`backend/test/vectors.json`) by both test suites. Regenerate it
deliberately with `npm run vectors`; never regenerate just to make a failing test pass.

## Superseded

The JSON snapshot cache, the PocketBase wire models and the API client were removed when local
storage became authoritative. The reasoning that justified a JSON file — that it was a
disposable cache, so a corrupt file only meant a cold start — stopped applying the moment it
held writes the server had never seen. Server sync will be rebuilt on `SyncPayload`, treating
PocketBase as one more peer.

## Earlier offline notes

The app is cache-first. Every successful sync writes a JSON snapshot of everything — summary,
subscriptions, price periods, all charges, upcoming, run rates — and startup paints from that
snapshot *before* contacting the server. So the app opens instantly and stays useful when the
server is down.

| | Location |
|---|---|
| Android | app-private `filesDir/cache/snapshot.json` — removed on uninstall |
| Windows | `%LOCALAPPDATA%\SubTracker\snapshot.json` |

Roughly 26 KB for five subscriptions and 84 charges, so even years of history stays small. Writes
go to a temp file and are then renamed, so a crash mid-write can't leave a truncated snapshot;
and a snapshot that fails to parse (an older schema, say) is discarded rather than crashing
launch. Both cases are covered by tests.

**Reads work offline. Writes do not**, and that is deliberate: the server generates the billing
schedule, so a locally queued subscription would sit there showing no charges and no cost until
it synced — worse than an honest refusal. When a sync fails but cached data exists, the app shows
an "Offline — showing saved data" banner with the age of the snapshot and a Retry button, rather
than an error over an empty screen.

The **summary figures are cached exactly as the server computed them**, never recalculated
locally. That preserves the single-source-of-truth property: offline you see numbers that were
correct at a stated time, never numbers a second implementation guessed at.

## First run

The app asks for a server address and a login. Use the PocketBase instance's Tailscale address
and a record from its `users` collection — **not** the superuser account, which lives in a
separate collection the app doesn't authenticate against.

## Pairing

Three routes, in order of how little you have to do:

1. **Scan a QR.** One device shows a code; the other scans it. The QR carries address,
   port and pairing code together — the address being the part that is tedious to look up and
   easy to get wrong.
2. **Nearby.** mDNS finds the device and fills in its address; you still type the code.
3. **By address.** Type both. Always works, including when a VPN adapter eats multicast.

The QR payload is a `subtracker://pair?h=…&p=…&c=…&n=…` link. A custom scheme rather than an
https URL deliberately: none of this should be resolvable on the internet, and a QR that opens a
browser is a phishing shape.

The device name in the QR is capped at 24 characters. An unbounded name pushed the code to a QR
version dense enough that it failed to decode — it looked perfectly fine on screen and simply
never scanned. `QrRoundTripTest` decodes generated codes back out of the image so that regresses
into a build failure rather than a mystery at pairing time.

Scanning is Android-only. ZXing rather than ML Kit, so no Google Play Services dependency — the
app is otherwise entirely Google-free and should run on a de-Googled phone. Camera permission is
requested when you tap Scan, never at launch, and everything works without it.

## Discovery notes

The mDNS TXT record carries the device id, and the *service name* carries the display name. That
distinction matters: an early version used the service name as the id, so a device could not
recognise its own advertisement and listed itself under "Nearby". Both platforms now publish
`id` and `name` as TXT attributes and read them back.

Device names default to something recognisable — the phone model on Android, the computer name
on Windows. Every device defaulting to the same string makes the pairing list useless.

## Toolchain notes

These cost a build cycle each and are not obvious from the error messages.

**AGP 9 rejects `com.android.library` in a Kotlin Multiplatform module.** Use
`com.android.kotlin.multiplatform.library` instead. It creates the Android target itself, so
there is no `androidTarget()` call and no top-level `android { }` block — configuration goes in
`kotlin { android { ... } }`.

**Don't apply `org.jetbrains.kotlin.android` with AGP 9.** It fails with a `BaseExtension` cast
error; AGP supplies Kotlin for Android modules itself. (The `android.builtInKotlin=false` escape
hatch exists but is already deprecated — don't reach for it.)

**`item` is a member of `LazyListScope`,** not something to import.

## Known deprecation warnings

- The `compose.runtime` / `compose.material3` shorthands in `shared/build.gradle.kts` are
  deprecated in Compose Multiplatform 1.11 in favour of explicit coordinates. They still work.
- `compose.materialIconsExtended` is pinned at 1.7.3 and receives no updates; the long-term move
  is Material Symbols as vector resources.
- Android host tests are not enabled for the shared module, so `commonTest` runs on the desktop
  target only. Adding `withHostTest {}` to the android target would run them on both — worth
  doing if any platform-specific code appears.
