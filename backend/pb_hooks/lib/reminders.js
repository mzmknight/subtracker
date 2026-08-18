"use strict";

/**
 * Renewal reminders, computed rather than read.
 *
 * Charges used to be rows the projection wrote; now every device derives its
 * own, so the server derives them too — in memory, from the same engine — at
 * the moment it needs them. The server sends these because it is the only
 * participant guaranteed to be awake at 8am.
 */

var engine = require(__hooks + "/lib/engine.js");
var dates = require(__hooks + "/lib/dates.js");
var money = require(__hooks + "/lib/money.js");
var merge = require(__hooks + "/lib/merge.js");
var peering = require(__hooks + "/lib/peering.js");

function todayString() {
  var now = new Date();
  return dates.formatDate({ y: now.getFullYear(), m: now.getMonth() + 1, d: now.getDate() });
}

function loadSettings(app) {
  var records = app.findAllRecords("settings");
  if (records.length === 0) {
    return { home_currency: "GBP", reminder_days: 3, ntfy_url: "", ntfy_topic: "" };
  }
  var r = records[0];
  return {
    home_currency: r.getString("home_currency") || "GBP",
    reminder_days: r.getInt("reminder_days"),
    ntfy_url: r.getString("ntfy_url"),
    ntfy_topic: r.getString("ntfy_topic"),
  };
}

/**
 * Charges falling due between today and today + days, with overrides applied.
 * Deleted records are excluded — a tombstone is still a row, and forgetting
 * that would remind you about subscriptions you cancelled.
 */
function upcoming(app, today, days) {
  var subscriptions = merge.live(peering.readAll(app, "subscriptions"));
  var prices = merge.live(peering.readAll(app, "price_periods"));
  var overrides = merge.live(peering.readAll(app, "charge_overrides"));

  var horizon = dates.addDays(today, days);
  var out = [];

  for (var i = 0; i < subscriptions.length; i++) {
    var sub = subscriptions[i];
    var subPrices = prices.filter(function (p) {
      return p.subscription === sub.id;
    });
    if (subPrices.length === 0) continue;

    var occurrences;
    try {
      occurrences = engine.generateOccurrences(sub, today, horizon, today);
    } catch (err) {
      // One malformed subscription must not stop the others being reported.
      continue;
    }

    for (var j = 0; j < occurrences.length; j++) {
      var due = occurrences[j];
      var override = null;
      for (var k = 0; k < overrides.length; k++) {
        if (overrides[k].subscription === sub.id && overrides[k].due_date === due) {
          override = overrides[k];
          break;
        }
      }
      if (override && (override.kind === "skipped" || override.kind === "refunded")) continue;

      var price = engine.resolvePriceAt(subPrices, due);
      var amount = override && override.kind === "amount"
        ? override.amount_minor
        : price.amount_minor;

      out.push({ name: sub.name, due: due, amount_minor: amount, currency: sub.currency });
    }
  }

  out.sort(function (a, b) {
    return dates.compareDate(a.due, b.due);
  });
  return out;
}

function send(app) {
  var settings = loadSettings(app);
  if (!settings.ntfy_url || !settings.ntfy_topic) {
    return { sent: 0, reason: "ntfy not configured" };
  }

  var today = todayString();
  var due = upcoming(app, today, settings.reminder_days);
  if (due.length === 0) return { sent: 0 };

  // Written to be readable on a lock screen: amount with its symbol, the name,
  // and when it lands in words rather than an ISO date the reader has to decode.
  var lines = [];
  var total = 0;
  for (var i = 0; i < due.length; i++) {
    var charge = due[i];
    total += charge.amount_minor;
    lines.push(
      money.format(charge.amount_minor, charge.currency) +
        "  " + charge.name +
        "  " + dates.describeDue(charge.due, today) +
        " (" + dates.formatShort(charge.due) + ")"
    );
  }

  var noun = due.length === 1 ? "charge" : "charges";
  var title = due.length + " subscription " + noun + " due soon";
  var body = lines.join("\n");
  if (due.length > 1) {
    body += "\n\nTotal " + money.format(total, settings.home_currency);
  }

  var base = settings.ntfy_url.replace(/\/+$/, "");
  $http.send({
    url: base + "/" + settings.ntfy_topic,
    method: "POST",
    body: body,
    headers: {
      Title: title,
      Tags: "credit_card",
      Priority: "default",
    },
    timeout: 15,
  });

  return { sent: due.length, total_minor: total };
}

module.exports = {
  todayString: todayString,
  loadSettings: loadSettings,
  upcoming: upcoming,
  send: send,
};
