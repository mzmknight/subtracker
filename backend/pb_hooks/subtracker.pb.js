/// <reference path="../pb_data/types.d.ts" />

/**
 * SubTracker hook registrations.
 *
 * The server is now a sync peer, not the owner of the data. It stores only
 * intent — subscriptions, price periods and charge overrides — and every device
 * computes its own charges from those. So there is no projection cron here any
 * more: nothing on the server needs charges to exist as rows.
 *
 * IMPORTANT: PocketBase evaluates every hook callback in its own scope. Nothing
 * declared at the top level of this file is visible inside the callbacks below,
 * so each handler requires what it needs itself. Getting this wrong fails at
 * runtime with "X is not defined" and, in an after-success hook, rolls back the
 * user's write.
 */

// The sync exchange. Same protocol as device-to-device: send everything, get
// back only what you were missing.
routerAdd(
  "POST",
  "/api/subtracker/sync",
  function (e) {
    var peering = require(__hooks + "/lib/peering.js");
    var payload = e.requestInfo().body || {};

    var result = peering.exchange(e.app, {
      subscriptions: payload.subscriptions || [],
      prices: payload.prices || [],
      overrides: payload.overrides || [],
    });

    console.log(
      "[subtracker] sync from " + (payload.deviceName || "unknown device") +
        ": applied " + result.applied + ", returning " +
        (result.payload.subscriptions.length + result.payload.prices.length +
          result.payload.overrides.length)
    );

    return e.json(200, {
      deviceId: "server",
      deviceName: "Server",
      protocol: 1,
      payload: result.payload,
    });
  },
  $apis.requireAuth()
);

// Lets a device confirm it is talking to a SubTracker server before syncing.
routerAdd(
  "GET",
  "/api/subtracker/hello",
  function (e) {
    return e.json(200, { deviceId: "server", deviceName: "Server", protocol: 1 });
  },
  $apis.requireAuth()
);

/**
 * Morning reminder for charges about to fall due — off unless asked for.
 *
 * Devices now compute and post their own reminders, with per-subscription rules
 * and a time of day the user chose. This cron knows none of that: it would push
 * a second, differently-worded ntfy message for the same renewal, at 8am
 * regardless, ignoring every subscription set to "never".
 *
 * It is kept because it is the one participant that is always on, which makes it
 * genuinely useful for anyone who wants a push that does not depend on their
 * phone — but it has to be opted into, or deploying the server would quietly
 * start double-notifying.
 */
if ($os.getenv("SUBTRACKER_SERVER_REMINDERS") === "1") {
  cronAdd("subtracker_reminders", "0 8 * * *", function () {
    var reminders = require(__hooks + "/lib/reminders.js");
    var result = reminders.send($app);
    if (result.sent > 0) {
      console.log("[subtracker] reminded about " + result.sent + " charge(s)");
    }
  });
} else {
  console.log(
    "[subtracker] server-side reminders are off; devices handle their own. " +
      "Set SUBTRACKER_SERVER_REMINDERS=1 to also push from here."
  );
}

// Fire a reminder push on demand, to check the ntfy wiring without waiting
// until tomorrow morning.
routerAdd(
  "POST",
  "/api/subtracker/test-reminder",
  function (e) {
    var reminders = require(__hooks + "/lib/reminders.js");
    return e.json(200, reminders.send(e.app));
  },
  $apis.requireAuth()
);
