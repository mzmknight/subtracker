# SubTracker backend

PocketBase v0.39.7 + a projection engine that turns each subscription's schedule into concrete
dated charges.

```
engine/          pure JS: dates, money, projection. No PocketBase, no clock, no I/O.
test/            node --test suite over engine/ — 27 tests
pb_migrations/   schema: 4 collections + 4 aggregate views
pb_hooks/        cron jobs, record hooks, custom endpoints
pb_hooks/lib/    GENERATED copy of engine/ (npm run sync-hooks)
scripts/         the sync script
```

## Working on it

```bash
npm test
```

```bash
npm run build
```

`build` runs the tests and then regenerates `pb_hooks/lib/`. **Never edit `pb_hooks/lib/`
directly** — it is overwritten. `engine/` is the source of truth and the only thing under test.

The engine is written in a conservative CommonJS subset so the identical file runs under both
Node and PocketBase's goja runtime.

## Deploying to Proxmox

### 1. Container

Create a Debian 12 LXC on the PVE host (<your-pve-host>) — 1 vCPU, 512 MB RAM, 4 GB disk is
ample. Give it a static IP. Keep it separate from any other PocketBase you run: different
schema, different backup schedule, and a bad migration on one cannot touch the other.

Set the container timezone to your own. The projection uses the server's local date to decide
what counts as "today", so leaving a container on UTC can flip the day early wherever local time
is ahead of it, such as during a summer-time offset.

```bash
timedatectl set-timezone <your-timezone>
```

### 2. PocketBase

```bash
mkdir -p /opt/subtracker && cd /opt/subtracker
curl -L -o pb.zip https://github.com/pocketbase/pocketbase/releases/download/v0.39.7/pocketbase_0.39.7_linux_amd64.zip
unzip pb.zip && rm pb.zip
```

Copy `pb_migrations/` and `pb_hooks/` (including the generated `lib/`) into `/opt/subtracker/`.

```bash
/opt/subtracker/pocketbase superuser upsert you@example.com 'a-long-password'
```

### 3. systemd

`/etc/systemd/system/subtracker.service`:

```ini
[Unit]
Description=SubTracker PocketBase
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/subtracker
ExecStart=/opt/subtracker/pocketbase serve --http=0.0.0.0:8090
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl enable --now subtracker
```

Migrations apply automatically on start. Watch the first boot with
`journalctl -u subtracker -f` — a schema error appears there and nowhere else.

### 4. A user to log in as

The collection rules require an authenticated user, and superusers are a separate collection
from `users`. Create one real `users` record in the admin UI at `/_/` — that is the account the
apps sign in with.

### 5. Tailscale

If the existing Tailscale CT already advertises `<your-lan-subnet>` as a subnet route, nothing to
do — the apps just use the LAN address. Otherwise install Tailscale inside this container; an
unprivileged LXC needs `/dev/net/tun` added to its config first.

### 6. Backups

Two layers, because they fail differently:

- PocketBase's own scheduled backups (Settings → Backups) catch a bad migration or a mistaken delete.
- A Proxmox `vzdump` of the container catches the container itself dying.

Test a restore once, before there is data worth losing.

### 7. Reminders

Stand up ntfy in its own small LXC, then set `ntfy_url` and `ntfy_topic` on the single `settings`
record. `reminder_days` controls the look-ahead window (default 3).

Check the wiring without waiting for 8am:

```bash
curl -X POST -H "Authorization: $TOKEN" http://<host>:8090/api/subtracker/test-reminder
```

It returns `{"sent": n, "total_minor": n}`, or `{"sent":0,"reason":"ntfy not configured"}` if the
settings are still blank — the job never throws when unconfigured, it just does nothing.

Install the ntfy app on your phone and subscribe to the same topic. A published reminder looks
like this (verified against a real ntfy 2.27.0):

```
Title: 2 subscription charges due soon
Tags:  credit_card

£10.99  Netflix  tomorrow (10 Aug)
£12.99  Spotify  in 2 days (11 Aug)

Total £23.98
```

Sorted by due date, amounts carry their currency symbol, and dates are relative because a
lock-screen notification reading "2026-08-11" makes you do the arithmetic yourself. The total
line only appears when there is more than one charge. Skipped and refunded charges are excluded,
as are tombstoned subscriptions — forgetting that would remind you about things you cancelled.

## The server is a peer, not the owner

Devices hold the data and compute their own schedules. PocketBase's job is to be
*the peer that is always on* — useful when your phone and laptop are rarely awake at the same
time, and never required for the app to work.

Consequences that surprise people reading the schema:

- **Charges are not stored.** The `charges` table and the three aggregate views were dropped:
  charges are a pure function of a subscription and its price history, and every device
  recomputes them. Keeping them here would be a second source of truth.
- **IDs come from devices.** A subscription created on a phone with no network keeps its id
  forever, so the server accepts client-supplied ids rather than minting its own.
- **Every synced record carries `updated_at` and `deleted`** — a hybrid logical clock and a
  tombstone. The server merges on exactly the same last-write-wins rules as the devices
  (`engine/merge.js`, mirroring `Merge.kt`), so syncing with the server and syncing with a phone
  produce the same result.
- **Reminders are computed, not read.** The 8am job expands the schedule in memory using the same
  engine, because there are no charge rows to query.

Verified against a live instance: an older edit loses and is corrected, a newer edit wins, a
tombstone persists as a row, and a stale device trying to revive a deleted subscription is
refused *and* sent the correction.

## Endpoints beyond the standard PocketBase API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/subtracker/sync` | the merge exchange: send everything, receive what you lack |
| GET | `/api/subtracker/hello` | confirm this is a SubTracker server before syncing |
| POST | `/api/subtracker/test-reminder` | fire the ntfy push immediately |

All three require authentication.

## Scheduled jobs

| Cron | Job |
|---|---|
| `0 8 * * *` | push a reminder for charges due within `reminder_days` |

There is no projection cron any more — the devices project their own charges.

## Things that will bite you

These each cost a debugging cycle during the build. They are handled in the committed code, but
they will reappear on any new view or hook.

**A view's outer SELECT must be plain identifiers.** PocketBase derives a view's fields by
parsing its SELECT list; any expression there fails with `invalid identifier parts`, which then
surfaces as a misleading `fields: cannot be blank`. Use the `viewOf()` helper — it wraps the real
query in a subquery and projects bare columns.

**Hook callbacks don't share their file's scope.** A function defined at the top of
`subtracker.pb.js` is not visible inside a `cronAdd` or `onRecord*` callback; it throws
`X is not defined`, and inside an after-success hook that rolls back the user's write. Every
handler `require()`s what it needs itself.

**View columns come back JSON-typed.** `record.getInt()` returns `0` and `record.getString()`
returns the value *with JSON quotes attached* (`"2026-08"`, not `2026-08`). This is silent — the
comparison just fails and the dashboard reads zero. Use `viewNum()` / `viewStr()` from
`projector.js` for anything read out of a `v_*` collection.

**Booleans compare as `1`, not `TRUE`,** in view SQL.

## Data model in one paragraph

A `subscription` holds a schedule (`cycle_unit` × `cycle_count` from an `anchor_date`), never a
price. Prices live in `price_periods`, one row per price, so each charge is valued at the price
in force on its own due date and last year's total doesn't silently change when a vendor puts
its price up. The engine expands the schedule into `charges` rows, which is what every view and
every client actually reads. A charge is engine-owned only while
`status = 'projected' AND NOT has_override AND source = 'generated'` — anything you skip, refund,
correct, or import belongs to you and is never rewritten.
