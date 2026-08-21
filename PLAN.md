# SubTracker — build plan

Self-hosted subscription tracker. Android + Windows clients, PocketBase backend on Proxmox,
reachable over Tailscale. Manual entry now, email import designed for but not built.

---

## 1. Decisions

| Area | Choice | Why |
|---|---|---|
| Clients | **Compose Multiplatform** (Kotlin) — Android + Windows JVM desktop | Native apps on both, from ~90% shared Kotlin, using Compose + Ktor. |
| Backend | **PocketBase**, new instance in its own LXC | v0.39.7 can do everything this app needs — see §2 for the three things to work around. |
| Billing maths | **Server-side**, materialised into a `charges` table | The date arithmetic is the only genuinely tricky code here. Write it once in JS, not twice in Kotlin and again later in an import worker. |
| Access | Tailscale only | Nothing exposed publicly, no certs. |
| Money | Integer minor units (pence) everywhere | Never floats. See §6. |
| Dates | Plain `YYYY-MM-DD` text, never datetimes | Kills the entire class of timezone off-by-one-day bugs. See §6. |

### On Compose Multiplatform vs. two separate apps

Native Android + Windows desktop normally means two UI codebases. CMP
collapses that: one `@Composable` screen set that compiles to an Android APK and a Windows
`.msi`. Shared module holds models, Ktor client, repository, and formatting.

Cost of this choice: the Windows build is JVM, so `jpackage` bundles a runtime — the installer
lands around 60–90 MB. For a personal tool that's a non-issue.

The fallback, for anyone not wanting to learn CMP, is Compose Android + an Electron desktop
app talking to the same API. It works, it's just two UIs to
keep in step. Everything else in this plan is unchanged either way.

---

## 2. Will PocketBase actually do all of it?

Yes — now verified against a real v0.39.7 instance, not just assumed. The schema applies, the
hooks fire, the projection is idempotent, and every dashboard figure matches hand-calculation.

Three things to design around, known before starting:

**It has no `GROUP BY` / `SUM` in the REST API.** You cannot ask the collection API for "total
per month". Fix: **view collections** — a view collection is a SQL `SELECT` that PocketBase
exposes as a read-only collection. All the dashboard aggregates become views (§5). Gotcha: a
view collection's `SELECT` *must* produce a column named `id`, so grouped queries need something
like `SELECT strftime('%Y-%m', due_date) AS id, ...`.

**Number fields are float64.** Storing `9.99` and summing 400 of them drifts. Fix: store
`amount_minor` as an integer number of pence and divide only at display time.

**Date fields are UTC datetimes.** A billing date is a calendar date, not an instant — if you
store "1 Aug" as a datetime it becomes 31 July for someone, somewhere, eventually. Fix: billing
dates are plain `text` fields holding `YYYY-MM-DD`. They sort and compare correctly as strings,
and SQLite's `strftime`/`date()` read them natively.

What PocketBase gives you for free that matters here: auth, the admin UI for schema edits, JS
hooks with `cronAdd` for the nightly projection and reminder jobs, `routerAdd` for custom
endpoints, scheduled backups, and file-copy disaster recovery.

### Four things only running it revealed

Each of these cost a debugging cycle. They are all handled in the committed code, but they will
bite again on any new view or hook.

**A view's outer SELECT must be plain identifiers.** PocketBase parses the SELECT list to derive
the view's fields, and any expression there fails with `invalid identifier parts` — which then
cascades into a confusing `fields: cannot be blank`. Every view is therefore written as a
subquery doing the real work, wrapped in an outer `SELECT t.col AS col FROM (...) t`. The
`viewOf()` helper in the migration does this.

**Hook callbacks do not share their file's scope.** A helper function defined at the top of
`subtracker.pb.js` is *not* visible inside a `cronAdd` or `onRecord*` callback — it fails at
runtime with `X is not defined`, and inside an after-success hook that rolls back the user's
write. Every handler must `require()` what it needs itself. This is why all the real logic lives
in `pb_hooks/lib/projector.js`.

**View columns are not typed like base-collection columns.** Computed columns (`SUM`, `CAST`,
`CASE`, `strftime`) come back JSON-typed, so `record.getInt()` returns `0` and `getString()`
returns the value *with its JSON quotes still attached* — `"2026-08"` rather than `2026-08`.
Silent and nasty: every month-key comparison just quietly fails and the dashboard reads zero.
The `viewNum()` / `viewStr()` helpers in `projector.js` normalise this; use them for anything
read out of a `v_*` collection.

**Booleans compare as `1`, not `TRUE`,** in view SQL.

---

## 3. Data model

Four collections. The shape matters more than anything else in this plan — get it right and the
features fall out; get it wrong and "how much did I pay last year" quietly lies to you.

### `subscriptions`
| Field | Type | Notes |
|---|---|---|
| `name` | text, required | "Netflix" |
| `vendor` | text | free-text now; the matching key for email import later |
| `category` | select | entertainment, software, utilities, health, finance, other |
| `currency` | text | ISO 4217, default `GBP` |
| `cycle_unit` | select | `day` / `week` / `month` / `year` |
| `cycle_count` | number | every N units — `month`+`3` = quarterly. Covers every real-world cycle with two fields. |
| `anchor_date` | text `YYYY-MM-DD` | the **first ever** billing date. All future dates derive from this, never from the previous charge. |
| `status` | select | `active` / `trial` / `paused` / `cancelled` |
| `end_date` | text date, nullable | set when cancelled; projection stops here |
| `trial_end` | text date, nullable | drives the "cancel before you're charged" alert |
| `payment_method` | text | "Amex", "Monzo" — lets you answer "what's on this card?" |
| `colour` / `icon` | text | UI only |
| `notes` | text | |

### `price_periods`
| Field | Type | Notes |
|---|---|---|
| `subscription` | relation | |
| `amount_minor` | number (int) | pence |
| `effective_from` | text date | |

One row per price. `effective_to` is implied by the next row's `effective_from`.

**Why this isn't just an `amount` column on the subscription:** when Netflix raises the price,
a single column silently rewrites your history — last year's total recalculates at this year's
price. Price periods mean each charge is valued at the price that was actually in force. This is
the one piece of normalisation worth the extra table.

### `charges` — materialised occurrences
| Field | Type | Notes |
|---|---|---|
| `subscription` | relation | |
| `due_date` | text date | |
| `amount_minor` | number (int) | resolved from the price period covering `due_date` |
| `currency` | text | snapshotted |
| `status` | select | `projected` / `skipped` / `refunded` |
| `has_override` | bool | true when the user corrected the amount |
| `actual_amount_minor` | number | meaningful only when `has_override` |
| `source` | select | `generated` / `manual` / `email` ← import writes here later |
| `external_ref` | text | dedupe key for email/bank import later; empty for now |

Paid-vs-upcoming is **derived** (`due_date < today`), not stored. Storing a `posted` status
would mean a nightly job mutating rows, and would freeze history the moment a charge came due —
so correcting last March's price retroactively would silently do nothing.

`has_override` exists because PocketBase returns an empty number field as `0`, which is
indistinguishable from a real £0.00 charge. An explicit boolean is one extra field and removes
the ambiguity entirely.

Unique index on `(subscription, due_date)`. This is what makes the nightly job an idempotent
upsert instead of a duplicate factory.

### `settings` — single record
Home currency, projection horizon (months forward/back), reminder lead days, ntfy topic.

---

## 4. The projection engine

A JS hook in `pb_hooks/`, run by `cronAdd` nightly at 03:00 and again on any write to
`subscriptions` or `price_periods`.

For each subscription, walk occurrences from `anchor_date` until
`min(end_date, today + horizon)` and upsert a `charges` row for each.

Four rules that are the whole ballgame:

1. **Always step from the anchor, never from the previous occurrence.** Occurrence *n* is
   `anchor + (n × cycle_count) units`. If you chain off the previous date, one month-end clamp
   corrupts the schedule permanently: 31 Jan → 28 Feb → **28 Mar** (wrong; should be 31 Mar).
2. **Clamp day-of-month to the target month's last day.** Anchor on the 31st bills on the 30th
   in April and the 28th/29th in February.
3. **Leap years.** A yearly subscription anchored 29 Feb bills 28 Feb in common years, and
   returns to the 29th in leap years — which rule 1 gives you for free.
4. **Never overwrite user truth.** A row is engine-owned only when
   `status === 'projected' && !has_override && source === 'generated'`. Everything else — an
   explicit skip, a refund, a corrected amount, a row that arrived from email import — belongs
   to the user and is never repriced or deleted. Regenerating must be safe to run at any time.

**Status: built and passing** — `backend/engine/`, 27 tests under `node --test`. Covers 31st
anchors across Feb/Apr, 29 Feb yearly across a leap cycle, weekly/fortnightly/every-N-day
cycles, mid-history price changes, cancellation and pause truncation, orphan removal when the
anchor moves, protection of user-touched rows, and a second run producing zero changes.

The engine is pure — no clock, no database, `today` is injected — so it runs unchanged under
both `node --test` and PocketBase's goja runtime.

---

## 5. Numbers and views

Two totals that must never be conflated, both on the dashboard, labelled differently:

- **Cash flow** — `SUM(amount_minor)` of charges due in the period. *"You paid £84.31 in July."*
- **Run rate** — normalised monthly cost of everything active: `amount × (cycle length in
  months)⁻¹`. A £120/yr sub is £10/mo. *"Your subscriptions cost £62/month on average."*

You need both because a month with three annual renewals looks like a disaster on cash flow
alone, and run rate alone never tells you what's about to leave your account.

View collections to create:

| View | Purpose |
|---|---|
| `v_monthly_totals` | `GROUP BY strftime('%Y-%m', due_date)` — the 12-month bar chart, last month, YTD |
| `v_category_totals` | spend per category over a window |
| `v_upcoming` | charges due in the next 30 days, joined to subscription name/colour |
| `v_run_rate` | per-subscription normalised monthly cost, and the total |

Dashboard tiles: this month (posted + still projected), monthly run rate, last month with delta,
year to date, next 30 days (count + total), all-time paid.

Charts: 12-month cash-flow bars with future months outlined rather than solid; category
breakdown of run rate; per-subscription price history on the detail screen.

**Currency:** keep `currency` per subscription and per charge even if everything is GBP today.
If a USD subscription appears, add a `home_amount_minor` column snapshotted at charge time from
a free FX API (frankfurter.app). Converting at display time instead would make your historical
totals change every day, which is wrong.

---

## 6. Client structure

```
subtracker/
  PLAN.md
  backend/       PocketBase: engine, tests, migrations, hooks   (see backend/README.md)
  apps/
    shared/      KMP module — core (PlainDate, Money), data (models, Ktor client,
                 settings), ui (all Compose screens). Both platforms share all of it.
    androidApp/  Activity + SharedPreferences store
    desktopApp/  main() + java.util.prefs store + jpackage config
```

- **Networking:** Ktor client + kotlinx-serialization.
- **Local cache:** a JSON snapshot on disk, **built**. SQLDelight was the original plan and would
  be over-engineering here: the whole dataset is ~26 KB, the client already holds it in memory,
  and there are no queries to run against it — the aggregates come from the server. Startup
  paints from the snapshot before contacting the server, so the app works with the backend down.
  Reads only; writes require the server, because the server is what generates the schedule.
- **Windows packaging:** the Compose Gradle plugin's `nativeDistributions` block, target format
  `Msi`. Needs a JDK 17+ with `jpackage` on the build machine.

Clients do no billing maths. They read `charges` and the views and render. That's the payoff for
materialising server-side.

---

## 7. Infrastructure

- **New LXC** on the Proxmox host (<your-pve-host>), Debian 12, 1 vCPU / 512 MB / 4 GB. Check the
  community-scripts catalogue for a PocketBase entry; if there isn't one, a plain Debian LXC plus
  the PocketBase binary and a systemd unit is a ten-minute job.
- **Static IP** on the LAN, PocketBase on `:8090`.
- **Separate from any existing PocketBase instance.** Different schema, different backup schedule, and a
  bad migration on one can't touch the other.
- **Tailscale:** if your existing Tailscale CT already advertises `<your-lan-subnet>` as a subnet
  route, you're done. Otherwise install Tailscale inside the new LXC — an unprivileged container
  needs `/dev/net/tun` added to its config.
- **Backups, two layers:** PocketBase's built-in scheduled backups, plus a Proxmox `vzdump` of
  the container. Test a restore once, before you have data worth losing.
- **Notifications:** self-hosted **ntfy** in its own small LXC. The PocketBase cron posts
  "renewal in N days" and "trial ends in N days" to a topic; the ntfy Android app receives it. No
  Google/FCM dependency, works entirely over Tailscale. The desktop app can subscribe to the same
  topic over websocket.

---

## 8. Phases

| # | Phase | Output | Status |
|---|---|---|---|
| 0 | Provision | LXC + PocketBase, Tailscale reachable, backups scheduled | **not started** — steps in `backend/README.md` |
| 1 | Schema + engine | Collections, views, projection hook, date-logic tests | **done**, 27 tests green, verified on a live instance |
| 2 | Shared module | Models, Ktor client, settings storage | **done**, 12 tests green |
| 3 | Core UI | Subscription list, add/edit, detail with price history | **done**, compiles both platforms |
| 4 | Dashboard | Tiles + chart + category breakdown | **done**, reads the summary endpoint |
| 5 | Windows packaging | `.msi` via jpackage | **not started** — `./gradlew :desktopApp:packageMsi` |
| 6 | Notifications | ntfy + cron reminders, trial-end alerts | backend written, needs an ntfy host |
| 7 | *Later* | Email import worker writing `charges` with `source=email` | schema ready, not built |

Phase 1 was the one to be fussy about, and it earned it: every subsequent bug was toolchain
plumbing rather than logic.

### Verified end to end

Against a throwaway PocketBase v0.39.7 with five seeded subscriptions:

- A 31st-of-the-month anchor bills 31 Jan → 28 Feb → **31 Mar** — clamped without drifting.
- A 29 Feb yearly anchor bills 28 Feb in common years and returns to the 29th in 2028.
- A price rise dated 1 July repriced exactly the charges from 15 July onward.
- A subscription cancelled from 1 Feb stopped dead at its last charge on 10 Jan.
- Re-running the projection twice changed nothing: 84 charges, zero writes.
- Dashboard figures matched hand-calculation: this month £23.98, last month £59.98,
  year to date £409.86, run rate £43.90/month.
- The desktop app launched, authenticated with a cached token and fetched all four endpoints.

Not yet verified: how the dashboard actually *looks*, the Android app on a device, and the
`.msi` installer.

### Window insets — two defects only a real device showed

`MainActivity` calls `enableEdgeToEdge()`, so the window extends under the system bars and
*something* has to put the space back. The `Scaffold` was passing
`contentWindowInsets = WindowInsets(0, 0, 0, 0)`, which meant nothing did.

- **The page title drew under the status bar clock.** Fixed by insetting only the content, with
  `WindowInsets.safeDrawing.only(Top + Horizontal)`. Deliberately not applied at the `Scaffold`
  level: `NavigationBar` and `NavigationRail` already pad themselves, so doing both would double
  the gap under them. In rail mode the start side is dropped too, since the rail occupies it.
- **The keyboard covered the field being typed into.** Worse than it sounds — because the
  viewport never shrank, Compose believed the focused field was already visible and so never
  scrolled it up. You typed blind into the bottom of the edit form. Fixed with
  `Modifier.imePadding()` on the `Scaffold`.

Verified on a booted emulator, not by inspection: dashboard, subscriptions, devices, the
full-screen scanner overlay, the edit form with the keyboard raised, and landscape (which
crosses the 900.dp rail breakpoint).

### History, colours and logos

`colour` and `icon` had been columns on `subscriptions` since the first migration and were
already carried by `SubscriptionRecord` through the merge — they had simply never been written
to. So none of this needed a schema change on the device side.

**History** is its own tab. `History.months()` groups the projection by month and then by
subscription; because charges are derived, browsing five years back is a wider window rather
than a query against stored rows, and correcting a price you got wrong retroactively fixes
every month it touched.

Two decisions worth keeping:

- **It stops at the current month.** The first build ran the projection twelve months forward
  and sorted newest-first, so a screen called History opened on August *2027* and you scrolled
  through a year of forecasts to reach anything you had actually spent. The window still runs
  two months past today — a charge due on the 28th has to be in the month you are standing in —
  but anything beyond the current month is filtered out. `includeFuture = true` brings it back
  for callers that want it.
- **A year's total excludes forecast months.** Adding spent money to predicted money produces a
  figure that is neither, and it is the one people quote.

**Composition** is a stacked bar per month, in each subscription's own colour, with a legend
giving percentage and amount. Slices have a floor of 1.5% so a £1 line next to a £90 one is
still visible, which means widths stop being exactly proportional at the extremes — the printed
percentage is the precise figure. Percentages floor to a minimum of 1% for the same reason: a
row reading "0%" next to a non-zero amount contradicts itself.

**Colours** come from a brand table (`BrandColours`), falling back to a palette entry chosen by
hashing the name. Hashing rather than counting matters: two devices that add "Netflix"
independently have to agree, or a sync reshuffles the chart. Typing a recognised name updates
the swatch live until the user overrides it.

**Logos** are fetched from the vendor's own domain first — `apple-touch-icon.png`, then
`favicon.ico` — and only fall back to a third-party icon service when the vendor publishes
nothing usable. That ordering is the privacy decision: asking netflix.com for its icon tells
Netflix something it already knows, whereas asking an aggregator tells a third party the whole
subscription list. Fetching runs on an explicit press, not automatically.

The image is stored in the record as base64 rather than as a URL so that a logo fetched once on
the phone reaches the laptop by ordinary sync, and keeps working when the vendor moves the file.
The cost is payload size — every logo travels in full on every sync — so it is capped at 24 KB
after being re-encoded to PNG at 144px.

**The PocketBase field was too small.** `icon` was `max: 40`, sized for a short icon *name* on
the assumption the client would ship a bundled icon set. A base64 logo is roughly 30,000
characters. It would have failed validation on the first sync to the server and nowhere else —
the path least likely to be exercised while developing offline. Raised in
`1754960000_logo_field.js`; **this migration has not been applied to a running server**, because
there isn't one yet.

### Spent versus still to come

Every headline figure mixed money that had already left the account with money that had not.
"This month £28" on the 13th is mostly a forecast, and reading it as "I have spent £28" is wrong
in a way nothing on screen corrected.

`DashboardFigures` now carries `monthSpentMinor` / `monthRemainingMinor` and the same pair for
the calendar year, with `yearTotalMinor` and `yearProgress` derived from them. The month tile
says what has gone and what is still to come; a "2026 so far" card gives spent, remaining and
full-year with a progress bar.

Two details worth keeping:

- **A charge due today counts as still to come.** `isSettled` is `due < today`, deliberately. The
  money has not moved yet, and treating it as spent would overstate the figure by one charge on
  one day a month — invisible, and irritating once noticed.
- **Skipped charges are neither spent nor owed.** They value at zero, so they fall out of both
  sides rather than inflating the forecast.

Note `yearToDateMinor` is unchanged and still means "every whole month up to and including this
one". It is a different question from `yearSpentMinor` and both are worth having.

### Currencies, and a total that was quietly wrong

Every aggregate summed `effectiveAmountMinor` across all charges ignoring `currency`. Nothing
noticed because everything was GBP — but the moment a dollar subscription existed, £10.99 and
$23.76 were added to make "£34.75". That number is wrong and looks completely ordinary, which is
the worst kind of wrong. `CurrencyTest` pins the old answer alongside the new one so conversion
cannot silently stop happening.

**Rates are fetched automatically, and can be pinned.** They were hand-entered first, on the
argument that a rate service is one more thing to talk to — but a tracker that needs you to know
today's dollar rate before its totals are right is a tracker that is wrong most of the time. What
the request leaks is a list of currency codes, which is a far smaller thing than the logo lookup
already gives away, and much smaller than the subscription list it is not sending.

Source is the European Central Bank via Frankfurter: no account, no key, and one request covers
every currency the app offers in a couple of hundred bytes. The alternatives wanted an API key —
so the user would have to sign up for something before their totals were correct — or returned
three hundred currencies and a pile of cryptocurrencies to get at eight numbers.

Refreshed on launch, after every sync, and whenever a subscription is saved — throttled to twelve
hours, because reference rates are published once a day. The button on the Devices card ignores
the throttle.

The save trigger was missing at first, and it showed on a device: adding a dollar subscription
left the card saying "not fetched yet" about a currency plainly in use, because only launch and
sync asked. Adding something in a new currency is the exact moment its rate is wanted.

**A rate you type is never overwritten.** Manual and downloaded rates are stored separately and
manual always wins, so an automatic refresh cannot quietly disagree with a number you set
deliberately. Removing yours falls back to the live one rather than to nothing.

**The API answers the other way round** — how much foreign currency one pound buys — so every
figure is inverted on the way in. Getting that backwards produces rates wrong by the square of
the rate that still look like perfectly ordinary numbers, which is why `RateFetcherTest` runs the
real response shape through a local server and checks the arithmetic by hand. A zero rate is
dropped rather than inverted into infinity.

Decisions worth keeping:

- **Amounts stay in their own currency; only aggregates convert.** A Proton subscription reads
  `$19.80` on its own row and in its price history. The dashboard, History and the composition
  chart are all in the home currency.
- **An unknown currency passes through unconverted, and says so.** Dropping it would under-report
  a total and look plausible; visibly wrong beats quietly wrong. Both the edit form and the
  Devices card name the missing rate.
- **Zero and negative rates are rejected.** A zero rate erases a subscription from every total,
  which is indistinguishable from the app losing it.
- **Changing the home currency clears every rate.** They mean "per one unit, in the old home
  currency" and are meaningless afterwards.
- **Converting rescales minor units.** ¥1000 and £10.00 are both "1000 minor units"; a straight
  multiply would turn a thousand yen into a hundred thousand pence.

The home currency and any hand-typed rate sync as `setting` rows; downloaded rates do not, since
any device with a network can fetch them and syncing a stale set would only give it a way to
overwrite a fresh one.

One honest limitation, stated in the UI: a single rate per currency applies to all history, so
correcting a rate moves what past months appear to have cost.

### Billing cycles were never limited — only the UI was

"Every 2 years" already worked: `cycleCount` takes any number and the engine multiplies by it.
But with day/week/month/year on screen the chips read as the entire list of options and the small
"Every" box went unnoticed, so presets were added.

That then overcorrected: eleven chips — fortnightly, every 4 weeks, every 2 months, every 18
months, every 3 years — took two rows to read and mostly restated the controls underneath, while
still not covering the genuinely odd cycles. It is now four chips (weekly, monthly, quarterly,
yearly) plus **Custom**, which reveals the count-and-unit controls. Custom is pre-opened when the
stored cycle matches no preset, or a subscription billed every 18 months would open on a form
that appeared to disagree with it, and exactly one chip is ever lit so it is never ambiguous
which the save button will believe. The "Bills every 4 weeks." summary line is always visible,
preset or custom — it is the one place the thirteen-payments-a-year case is spelled out.

A reminder time can now be any time, via a clock picker, rather than one of eight presets. The
chip shows the chosen time rather than the word "Custom", so the row still says what the rule is.

### The icon

A pound sign inside an open circular arrow — money that comes round again, which is what the app
is about. The app's own navy and emerald, so the icon and the dashboard are recognisably one
thing.

Generated by `tools/IconGen.java` rather than drawn by hand, because an Android adaptive icon and
a Windows `.ico` maintained separately are two logos for one app. One run emits all of it:

- `mipmap-*/ic_launcher_foreground.png` — adaptive foreground, artwork inside the middle 66%
  because launchers mask and parallax the layers
- `mipmap-*/ic_launcher.png` — a flat 48dp fallback for anything still asking for a bitmap
- `drawable-*/ic_launcher_monochrome.png` — themed icons on Android 13+, a flat silhouette
  because the launcher recolours it and the emerald version would arrive as a solid slab
- `drawable-*/ic_notification.png` — white silhouette; Android tints notification icons and
  discards colour
- `apps/desktopApp/icons/subtracker.ico` — 16 through 256px

Two things that had to be got right rather than guessed:

- **The arrowhead pointed backwards.** A point on the arc traces `(cos θ, −sin θ)`, so the
  direction of travel is its derivative, `(−sin θ, −cos θ)`. The obvious-looking `(sin θ, cos θ)`
  is exactly reversed, and drew an arrow aimed back the way the arc came. Only visible by
  rendering it and looking.
- **The ICO is hand-assembled.** Java has no ICO writer and the format did not warrant a
  dependency: a 6-byte header, a 16-byte directory entry per image, then PNG payloads (understood
  since Vista, and what keeps a 256px entry from costing 256 KB). Verified by parsing the file
  back — 7 entries, each a real PNG lying inside the file — and by extracting the icon that
  jpackage embedded into `SubTracker.exe`.

`--preview` renders the sizes it will actually be seen at. An icon judged only at 512px is one
nobody has looked at properly.

### Review findings

A pass over everything added in this session. Six real defects, two of which lost data.

1. **PocketBase had no `notify_*` fields.** Reminder rules are on the subscription and therefore
   sync — but a field the server does not know is a field the server drops, so a round trip
   through PocketBase would silently turn every "never remind me about this one" back into
   "remind me like everything else". Fixed in `1755040000_reminder_fields.js`. This is the second
   time (see the `icon` field sized for a name and given an image): **anything added to
   `SubscriptionRecord` needs a PocketBase migration in the same breath.**
2. **CSV backup omitted reminder rules.** Export then restore reset every subscription to the
   default. Worse than the logo case, which was a deliberate exclusion with a carry-forward to
   compensate; this was an accidental omission with nothing. Three columns added, plus a test
   that a file written before reminders existed still imports with sensible defaults rather than
   "on the day, at midnight".
3. **`DateField` wiped itself on backspace.** The remembered text was keyed on
   `value.isEmpty()`. Deleting one character from a valid date made it unparseable → the reported
   value went blank → the key flipped → the remember re-seeded from that blank → the half-typed
   date vanished mid-keystroke. The text is now seeded once and owned by the field.
4. **`resetAllRemindersToGlobal` raced its own writes.** `mutate` launches a coroutine, so the
   reschedule that followed it ran *before* the writes landed and booked alarms from the old
   rules. Moved into `onSaved`.
5. **The server's ntfy cron now duplicates device reminders.** It would push a second,
   differently worded message at 8am regardless, ignoring every per-subscription rule. Deploying
   the server would have quietly started double-notifying. Now opt-in behind
   `SUBTRACKER_SERVER_REMINDERS=1`.
6. **Dead code and a stale name.** `Reminders.summarise` was never called — both notifiers build
   their own strings. `scanning2` was named when QR scanning was bolted on beside mDNS
   "scanning"; it is `scannerOpen`.

Clean: no compiler warnings anywhere in the shared module.

### Renewal reminders, on the device

The reminders that existed were a PocketBase cron posting to ntfy at 8am. With no server
deployed that meant no reminders for anyone, and it could not express a per-service rule. They
are now computed and posted by each device, so they work with no server at all.

**Where each setting lives, and why they differ.** Per-subscription rules are columns on
`subscription` and therefore sync: "never remind me about this one" is a fact about the
subscription, and having to repeat it on every device would make it not worth setting. The global
*rule* — days-before and time — syncs too, as a `setting` row: it describes how the user likes to
be reminded, not which machine they set it on. The global *switch* stays device-local in
`AppSettings`, because wanting the phone to nag and the laptop to stay quiet is entirely
reasonable and a synced switch cannot express it. See "Settings that follow the user" below.

**The global switch beats every per-subscription rule.** Otherwise "off" is a lie: a subscription
with its own settings would keep notifying after the user turned everything off.

Three things that only became clear while building it:

- **Initialisation had to move to `Application`.** The alarm and boot receivers run in a process
  with no Activity in it — after a reboot there may never have been one — and the database and
  settings store were installed by `MainActivity`. Reminders would have worked right up until the
  phone was restarted, then silently stopped.
- **Two reminders are kept per subscription, not one.** One is enough to *announce*, but the next
  alarm is booked from the same list, so a device holding a single subscription would announce it
  and then have nothing left to schedule — reminders would stop until the app was next opened.
- **`canNotify` had to become observable state.** It was a getter reading the notifier, so after
  granting permission in the system dialog the red "not allowing notifications" warning stayed on
  screen, which reads as the app being broken.

Alarms are deliberately **inexact**: an exact alarm needs `SCHEDULE_EXACT_ALARM`, which Android
asks users to justify and can revoke, and a renewal reminder landing within a few minutes of nine
is fine. `setAndAllowWhileIdle` still fires in Doze.

Announced reminders are keyed `subscriptionId@dueDate` and remembered, because the alarm chain
re-runs on every launch and every boot and must never say the same thing twice. Changing a rule
clears that memory, so moving from three days to seven can speak up about a renewal that is
already five days out.

**Desktop is the weaker half, and says so in the UI.** A tray notification only fires while
SubTracker is running; waking a desktop app at nine in the morning needs a scheduled task or a
service, and neither belongs in something meant to be unzipped and run from a folder. The phone
is the device that reliably notifies.

### Switching a plan, without rewriting what you already paid

Prices are versioned in `price_periods`; the billing cycle is not — it is a pair of columns on
the subscription. `occurrenceAt` computes every occurrence from the anchor on whatever cycle is
current, so editing monthly to yearly does not change the plan going forward, it retroactively
reshapes the past: a £10.99 charge in July is reported as £89.99, and the months between vanish.
Nothing warns you, and the resulting history looks entirely ordinary.

A switch is therefore **two records** — the old plan frozen where it stopped, the new one anchored
where it starts — joined by `replaces` on the newer one. `LocalStore.switchPlan` writes both plus
the new price in one transaction, because a half-applied switch leaves either two live plans
billing at once or none at all.

- **The old plan ends the day *before* the new one starts.** `endDate` is the last date a
  subscription can still bill on, so ending it on the switch date bills both plans that day. The
  edit form used to say "no charges are forecast on or after this date", which is the opposite of
  what the engine does and would have cost the user a duplicate charge; the copy now matches.
- **`replaces` points backwards, from new to old.** It is written once at creation and never
  touched. `superseded_by` on the old record would mean editing an old row on every switch, and
  each edit is another chance for two devices to disagree.
- **The list hides superseded records; History does not.** As a row it is the same subscription on
  old terms, so showing both makes one F1TV look like two — but its charges are real and still
  count in the month they fell in, and in "Paid across all plans" on the detail screen.
- **Name, logo, colour and reminder rule carry over.** To the user this is the same subscription on
  different terms; making them rebuild its appearance would make the feature not worth using.

`SwitchPlanTest` asserts the old plan keeps exactly the charges it made, that no day is billed by
both, and — deliberately — that a plain in-place edit still mangles the history, so anyone later
"simplifying" this into an edit gets a failing test explaining why not.

### Settings that follow the user

Every setting used to be device-local, which was right for some and wrong for others. The line
that matters is not what a setting controls but *who it is about*:

| About the user — syncs | About the device — stays put |
| --- | --- |
| reminder days-before, reminder time | whether this device may notify at all |
| home currency | rates downloaded from the ECB |
| hand-typed conversion rates | server URL, token, paired peers, announced reminders |

They live in a `setting` table with the same envelope as everything else — HLC stamp, tombstone —
so they merge through the existing `Merge.merge` unchanged, and ride both the peer payload and the
CSV backup.

**One row per key, not one row holding every setting.** Both merge, but a single blob makes two
unrelated edits collide: change the reminder time on the phone and the home currency on the
laptop, and last-write-wins keeps one and silently drops the other. Keyed separately, each is its
own contest and both land. `SettingsSyncTest` asserts exactly that.

**Writing an unchanged value is skipped**, or merely opening the settings screen would restamp
and beat a device that actually changed something.

**Upgrading carries the old values across.** A device that has had a home currency set for months
has an empty table on first launch; `AppSettings.useSyncedStore` seeds any key the table lacks
from the local store, so existing preferences start travelling instead of sitting there looking
set but never syncing.

Protocol went to 2. The field is additive and both ends parse with `ignoreUnknownKeys`, so a
protocol 1 peer and a protocol 2 peer still sync everything else; the old peer simply drops the
settings it does not understand.

**Not through the server.** `subtracker.pb.js` picks `subscriptions`, `prices` and `overrides`
out of the payload by name and ignores the rest, so settings sync device-to-device and by CSV,
but a device that only ever talks to PocketBase will not receive them. Closing that needs a
collection and a hook change on the server side.

### Why the logo finder worked for some services and not others

Reported as "worked on YouTube but not Amazon". Probing the actual endpoints explained it in one
go:

| URL | Amazon | YouTube |
| --- | --- | --- |
| `<domain>/apple-touch-icon.png` | 404 HTML | 404 HTML |
| `icons.duckduckgo.com/ip3/<domain>.ico` | 200, magic `00000100` — **true ICO** | 200, magic `89504e47` — PNG |
| `google.com/s2/favicons?domain=…` | 200, PNG | 200, PNG |

DuckDuckGo returns a PNG for some domains and a genuine BMP-encoded ICO for others — amazon.com
and netflix.com among them. Neither `ImageIO` nor `BitmapFactory` can decode an ICO, so those
downloaded *successfully* and then failed at the decode step, which is why the failure looked
like "no logo exists" rather than like a format problem. YouTube worked purely because DuckDuckGo
happened to answer PNG for it.

Writing an ICO decoder was considered and rejected: the Amazon file contains no embedded PNG
(checked), only BMP entries, so it would mean writing a BMP decoder too. Google's service answers
PNG for every domain tested, so it is now the final fallback.

Ordering is unchanged in spirit — vendor's own domain first, then DuckDuckGo as the more
privacy-respecting service, then Google only when the first two produce nothing decodable.

Also added: a browser-like `User-Agent`, since several CDNs answer 403 to an unrecognised client,
which surfaces as "no logo" rather than as anything to do with icons. And "Prime Video" now
resolves to primevideo.com rather than to the Amazon shop.

### File dialogs are the platform's, not Swing's

`JFileChooser` is drawn by Swing itself, so on Windows 11 it renders as a Windows-2000-era box
with none of the sidebar, address bar, search or pinned folders people actually navigate by.

`java.awt.FileDialog` calls the platform's own dialog instead — Explorer on Windows, Finder on
macOS — and gets overwrite warnings and shell folders for free. Both the backup dialogs and the
logo picker use it now, via `NativeFileDialogs`.

Two platform quirks it hides: Windows ignores `setFilenameFilter` completely and filters on the
`file` property instead, so both are set. And Linux has no native dialog for AWT to call and
falls back to something markedly worse than Swing's, so that one case still uses `JFileChooser`.

### Backup: one CSV, and why there are two ways to bring it back

Only *intent* is exported — subscriptions, price history, charge overrides. Charges are derived,
so exporting them would export a value any device can recompute and make the file enormous for
nothing. Two subscriptions with a price rise came to 872 bytes.

One file with a `type` column rather than three files in a zip: it opens in a spreadsheet with a
double-click, and a backup that needs a second tool to read is a backup people stop taking.

Decisions that took some care:

- **Amounts are written as `10.99`, not `1099`.** Parsed back with the same integer parser the
  form uses, so nothing is lost, but the file is readable by a human.
- **Dates are written ISO and read back with the day-first parser.** Excel rewrites `2026-01-15`
  as `15/01/2026` the moment it saves, so a backup that had been opened once would otherwise fail
  to import.
- **Columns are matched by name.** A file whose columns were reordered in a spreadsheet still
  imports.
- **A real RFC 4180 reader, not `split(",")`.** A note containing a comma shifts every column
  after it and imports garbage that looks entirely plausible.
- **Tombstones are exported.** A deleted subscription missing from the file would come back to
  life on the next sync with a device that still remembers it.
- **Override ids are recomputed** from (subscription, date) rather than trusted from the file, so
  an id edited in a spreadsheet cannot produce two records fighting over one charge.
- **Logos are excluded, and preserved anyway.** They are tens of KB of base64 each. But an
  imported record wins on timestamp, so without carrying the local `icon` forward, restoring a
  backup would silently blank every logo.

**Import merges; Restore overrides.** Import goes through the ordinary LWW merge, so an old
backup cannot undo newer work and importing twice is a no-op. That is right, and it also means
import *cannot recover a deletion* — deleting writes a tombstone stamped now, which beats
anything in an older file. Verified on a device: deleting a subscription and importing the backup
reported "Read 5 records, nothing was new" and left it deleted.

Since recovering a mistake is half of why anyone keeps a backup, `restoreCsv` restamps every
record with a fresh clock reading so the file wins. Restamping rather than force-writing matters
for a second reason: a paired device that still holds the delete adopts the restore on the next
sync instead of reverting it. Restore leaves records *not* in the file alone — removing them
would make it a rollback of everything since, which is a far more dangerous operation than the
button offers, so it asks for confirmation and says exactly what it found.

### UK dates, on the surface only

Records still hold `YYYY-MM-DD` and nothing about that changed. It cannot: the whole app leans on
lexicographic order being chronological — `month > thisMonth`, sorting by `effectiveFrom`,
`due.iso` as a list key — and a stored `09/08/2026` breaks every one of those silently.

So this is two conversions at the edges. `formatUk()` renders `09/08/2026` for text a user types
or reads back; `parseUserInput()` reads what they typed. Prose keeps `formatLong()` ("9 Aug
2026"), where a named month cannot be misread.

**Day first, decided once.** `01/02/2026` is 1 February, never 2 January. Nothing downstream can
catch a wrong reading — both are valid dates, it saves cleanly, and it bills a month early
forever. So the ambiguity is resolved in one place rather than by each caller, and the field
echoes the parsed date back as "1 Feb 2026" so the user can see which way it went. ISO is still
accepted on input because a four-digit leading component cannot be a day.

Impossible dates are rejected, not clamped: silently turning 31/02 into the 28th would move the
anchor, and the anchor is what every future charge is generated from.

`DateField` wraps all of it — typed entry plus a Material date picker — and is used by the three
date inputs (first billing date, cancelled-from, price-change-from). It hands the caller ISO, or
`""` while the text is unparseable, so a half-typed date reads as "no date yet" rather than as
the last valid one passed through on the way.

The picker reports UTC midnight millis; converting back uses `floorDiv`, because a truncating
divide lands a day late for any date before 1970.

### Making a long history readable

With 27 months on screen the flat list stopped working. Years are now cards that fold, with only
the most recent open by default, and each year carries its own breakdown — total, month count,
composition bar, and a legend of what it went on. Folding the months away to cut clutter would
otherwise throw away the only thing that made a year readable at a glance.

**Bar segments are drawn in a stable order, not in size order.** The legend is still largest
first, because "what dominated" is the question it answers. The bar is not: sorting segments by
size meant that the month Netflix overtook Spotify, the colours swapped sides, so the same two
subscriptions rendered green-then-red for half the year and red-then-green for the other half.
It reads as a rendering bug and it makes two months impossible to compare. Segments now sort by
name; the legend's coloured dots carry the identity, so the two orders never need to agree.

### Price Watch, and the gap it exposed

Price creep is the one thing the app could not answer. Per-subscription price history already
existed — it has to, because each charge is valued at the price in force on its own due date,
which is what stops a rise today from rewriting what last year cost. `PriceWatch` reads the same
table sideways: consecutive price periods per subscription, differenced.

**Nothing could record a price change.** `AppState.addPriceChange` existed and had no caller.
The edit form even told the user to "add a new price from the date it took effect" and pointed
at a control that was not there — so the price history table could only ever hold the opening
price written at creation, and Price Watch would have been permanently empty. The form now lives
on the Price history card in `DetailScreen`.

**Annualising via the monthly run rate was wrong.** `monthlyRunRate` rounds to the nearest penny
*per month*; multiplying that by twelve multiplies the rounding error too, so a £100/year plan
rising to £120/year reported as **£20.04**. Caught by a test written specifically to check that
a yearly plan's rise is not counted twelve times. `Money.annualRunRate` now rounds once, and
anything comparing two amounts over a year goes through it.

Two smaller judgements: a scheduled rise (dated in the future) is shown, because it is the most
actionable kind — still cancellable — but is **not** added to the "£X a year more than before"
figure, since it has not cost anything yet. And percentages round away from zero, so a 0.5% rise
does not print as "0%" next to a real increase.

Also added from the same review: top spenders, a cost-per-day figure, and sorting the list by
cost / name / next bill / cycle. Ended subscriptions always sink to the bottom whatever the sort
— letting a cancelled service head the list because it was the most expensive is misleading.

Deliberately **not** taken from that list: a pie chart (worse than the existing bars for
comparing magnitudes, and it degrades past about eight subscriptions) and a glassmorphic
restyle (it costs contrast on exactly the numbers being read). Home-screen and desktop widgets
are a genuine idea but a separate build — Android would need a Glance widget outside the shared
Compose tree, and Windows has no real equivalent short of a tray app.

### The APK is release-signed

`dist/SubTracker.apk` is now a signed release build — not `debuggable`, which matters for an app
holding peer pairing secrets on a shared network.

The key lives in `signing/`, read at build time from `keystore.properties` so the password is
not in source. **Losing that folder is unrecoverable**: Android refuses to update an app with a
build signed by a different key, so every future version would need an uninstall and reinstall
on every device. See `signing/README.md`.

If the folder is absent the release build still runs and produces an *unsigned* APK, which fails
with `INSTALL_PARSE_FAILED_NO_CERTIFICATES` — deliberately, so a checkout without the key stays
useful for development rather than failing the build outright.

R8 is still off (`optimization { enable = false }`). The app is sideloaded, so a smaller APK buys
nothing, and turning it on without keep rules for SQLDelight, Ktor and kotlinx.serialization is a
good way to ship something that only breaks at runtime.

**Changing the key is a one-time migration, done once.** Verified on a device: installing the
signed build over the debug one fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, so the old app
has to be uninstalled first — which takes the local database with it. Export a backup CSV first
and import it afterwards. Paired devices are *not* in that file (peer secrets are local-only and
deliberately never exported), so they have to be paired again.

---

## 9. Known risks

- **Month-end and leap-year arithmetic** — the top source of wrong numbers. Mitigated by rule 1
  in §4 and by testing the engine before any UI exists.
- **Float money** — mitigated by integer minor units, enforced by never adding a decimal field.
- **Timezone drift on billing dates** — mitigated by plain-date text fields.
- **View collections need an `id` column** — will fail confusingly on the first grouped view.
- **`jpackage` availability** on the Windows build machine — check early, not at phase 5.
- **Email import is a much bigger job than it looks.** Vendor receipt formats change without
  notice. The schema is ready for it (`source`, `external_ref`, `vendor`); treat the parsing
  itself as a separate project.
