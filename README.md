# SubTracker

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A subscription-cost tracker for Android and Windows that works with no server, no account and
no network. Two devices sync directly to each other over your own Wi-Fi by scanning a QR code.

<!--
  Screenshots go here. Drop the images in docs/screenshots/ and uncomment:

| Dashboard | Subscription | Edit |
| --- | --- | --- |
| ![Dashboard](docs/screenshots/dashboard.png) | ![Detail](docs/screenshots/detail.png) | ![Edit](docs/screenshots/edit.png) |
-->

## What it does

- Tracks what you pay, in **any billing cycle** — weekly, monthly, quarterly, yearly, or any
  whole number of days, weeks, months or years.
- Separates **cash flow** (what leaves your account this month) from **run rate** (what it all
  costs per month, normalised). They are different questions and are never added together.
- Keeps **price history**, so a price rise doesn't retroactively rewrite what last year cost.
- Handles **mixed currencies**, converting only aggregates and leaving each subscription in
  its own.
- Sends **renewal reminders** from the device itself, globally or per subscription.
- Backs up to **one CSV** that opens in a spreadsheet.

## Why it is built this way

The interesting parts of this project are the constraints, not the feature list.

**Local-first, not offline-first.** There is no "offline mode", because there is no online mode
to fall back from. Every read and write goes to the device's own SQLite database and the billing
projection runs there. Syncing merges into that; it is never required for the app to work. A
friend can run this with no server at all.

**Only intent syncs.** Charges are a pure function of a subscription's schedule and its price
history, so they are recomputed on each device rather than sent. A full sync payload is a few
kilobytes regardless of how many years of history exist.

**The merge is a CRDT, not a "last one to sync wins".** Records carry a
[hybrid logical clock](apps/shared/src/commonMain/kotlin/io/github/mzmknight/subtracker/sync/Hlc.kt) —
device clocks disagree by seconds or worse, and a plain wall-clock timestamp lets a later edit
silently lose. It is encoded fixed-width so lexicographic order *is* causal order, which means
SQL `ORDER BY` and a string compare both work without parsing. Merging is commutative,
associative and idempotent, so devices converge no matter what order they sync in.

**Deletes are tombstones.** "Absent" and "deleted" are indistinguishable during a merge, so a
hard delete gets resurrected by a peer's stale copy.

**Charge-override ids are derived, not random.** Two devices skipping the same charge must
produce the *same* record so last-write-wins can settle it, rather than two rows both claiming
one charge.

**The projection engine exists twice** — once in JavaScript for the optional server, once in
Kotlin for the device. They are kept honest by a generated corpus of test vectors that both
suites assert against, so the two implementations cannot drift apart unnoticed.

**Money is always an integer count of minor units, and dates are always plain `YYYY-MM-DD`
text.** No floats touch an amount; no date/instant type touches a calendar date. Both rules
exist because the alternative fails in ways that look completely ordinary — £10.99 and $23.76
summing to "£34.75", or a 31 January anchor chaining 28 Feb → 28 Mar and corrupting a schedule
permanently. Every occurrence is computed from the anchor date, never from the previous one.

The full design log, including the things that turned out to be wrong, is in [PLAN.md](PLAN.md).

## Installing it

Grab the latest build from the [Releases](../../releases) page.

**Android** — download the `.apk` and open it. Android will ask you to allow installing from
this source; that is expected for an app not distributed through the Play Store. Requires
Android 8.0 or newer.

**Windows** — download the portable `.zip`, extract it anywhere, and run `SubTracker.exe`.
Nothing is installed and nothing touches the registry; delete the folder to remove it.

### Syncing two devices

Open **Devices** on both, tap **Add a device** on one, and scan the QR code with the other. They
must be on the same Wi-Fi. After that they sync whenever you open the app.

## Building from source

Requires JDK 17+ and, for the Android app, the Android SDK.

```bash
git clone <this-repo>
cd subtracker/apps
./gradlew :desktopApp:run
```

Other useful targets — the Android debug APK, the Windows installer, and the no-install portable
zip respectively:

```bash
./gradlew :androidApp:assembleDebug
```

```bash
./gradlew :desktopApp:packageMsi
```

```bash
./gradlew :desktopApp:packagePortable
```

Release signing is read from `signing/keystore.properties`, which is deliberately not in this
repository. Without it the release build simply comes out unsigned rather than failing, so a
fresh clone builds and runs without needing any key. See [apps/README.md](apps/README.md) for
more, including how the portable and installed builds keep their data apart.

## Running the tests

258 tests across two suites, both run in CI on every push.

The shared Kotlin module — projection, money, dates, history, merge, sync, CSV round-trips, and
the local store against a real in-memory SQLite rather than a fake:

```bash
cd apps && ./gradlew :shared:desktopTest
```

The JavaScript projection engine, plus the shared vector corpus that keeps it agreeing with the
Kotlin one:

```bash
cd backend && npm test
```

## Project layout

| Path | What lives there |
| --- | --- |
| `apps/shared` | Nearly everything — models, projection, sync, and the Compose UI shared by both platforms |
| `apps/androidApp` | Android entry point, notifications, camera QR scanning |
| `apps/desktopApp` | Windows entry point, native file dialogs, packaging |
| `backend/engine` | Projection engine in plain JS, no PocketBase dependency |
| `backend/pb_hooks` | PocketBase hooks (generated from `engine/` — never hand-edited) |
| `tools` | Icon generation |

The optional [PocketBase backend](backend/README.md) is genuinely optional. It predates the
peer-to-peer sync and is kept for anyone who wants a always-on copy; the apps do not need it.

## Licence

[MIT](LICENSE).
