"use strict";

/**
 * The billing projection engine.
 *
 * Turns (subscription + price history) into a concrete list of dated charges,
 * then diffs that against what is already stored so the caller can apply an
 * idempotent upsert. All logic here is pure: no clock, no database, no I/O.
 * "today" is always injected so tests are deterministic.
 *
 * Field names are snake_case throughout to match the PocketBase collections
 * exactly — the records go from SQLite to this engine to the Kotlin clients
 * without a renaming layer in between.
 */

var d = require("./dates.js");
var money = require("./money.js");

var DEFAULT_MONTHS_BACK = 36;
var DEFAULT_MONTHS_FORWARD = 24;

/** Runaway guard. The fast-forward below means real inputs never approach this. */
var MAX_ITERATIONS = 100000;

var SUBSCRIPTION_STATUSES = ["active", "trial", "paused", "cancelled"];
var CHARGE_STATUSES = ["projected", "skipped", "refunded"];

/**
 * The date of the nth occurrence, counted from the anchor.
 *
 * This is deliberately a function of n rather than of the previous occurrence.
 * Stepping from the previous date lets a single month-end clamp corrupt the
 * whole schedule: 31 Jan -> 28 Feb -> 28 Mar, when March should return to the
 * 31st. Anchoring every occurrence to the original date makes clamping a
 * display-level correction that never accumulates.
 */
function occurrenceAt(sub, n) {
  var unit = sub.cycle_unit;
  var count = sub.cycle_count;
  if (unit === "month") return d.addMonths(sub.anchor_date, n * count);
  if (unit === "year") return d.addMonths(sub.anchor_date, n * count * 12);
  if (unit === "week") return d.addDays(sub.anchor_date, n * count * 7);
  return d.addDays(sub.anchor_date, n * count);
}

/**
 * Last date this subscription can still produce a charge on.
 *  - cancelled: stops at end_date, or today if none was recorded
 *  - paused:    keeps its history but generates nothing in the future
 *  - end_date:  always caps, whatever the status
 */
function generationEnd(sub, today, windowEnd) {
  var end = windowEnd;
  if (sub.status === "paused") {
    end = d.minDate(end, today);
  }
  if (sub.status === "cancelled") {
    end = d.minDate(end, sub.end_date ? sub.end_date : today);
  }
  if (sub.end_date) {
    end = d.minDate(end, sub.end_date);
  }
  return end;
}

/**
 * Skip straight to the first occurrence that could land inside the window
 * instead of walking from the anchor. A daily subscription anchored ten years
 * ago would otherwise cost 3,650 wasted iterations on every run.
 *
 * Deliberately lands one step early: month clamping makes the mapping from n
 * to date very slightly non-uniform, and starting a step short is free.
 */
function firstCandidateIndex(sub, windowStart) {
  var unit = sub.cycle_unit;
  var count = sub.cycle_count;
  var n;
  if (unit === "month" || unit === "year") {
    var stepMonths = unit === "year" ? count * 12 : count;
    var months = d.monthsBetween(sub.anchor_date, windowStart);
    n = Math.floor(months / stepMonths);
  } else {
    var stepDays = unit === "week" ? count * 7 : count;
    var days = d.toEpochDay(windowStart) - d.toEpochDay(sub.anchor_date);
    n = Math.floor(days / stepDays);
  }
  return Math.max(0, n - 1);
}

function validateSubscription(sub) {
  if (!sub || typeof sub !== "object") throw new Error("subscription is required");
  if (SUBSCRIPTION_STATUSES.indexOf(sub.status) === -1) {
    throw new Error(
      'status must be one of ' + SUBSCRIPTION_STATUSES.join("/") + ', got "' + sub.status + '"'
    );
  }
  money.assertCycle(sub.cycle_unit, sub.cycle_count);
  d.parseDate(sub.anchor_date);
  if (sub.end_date) {
    d.parseDate(sub.end_date);
    if (d.compareDate(sub.end_date, sub.anchor_date) < 0) {
      throw new Error("end_date is before anchor_date for subscription " + sub.id);
    }
  }
}

/**
 * Every billing date for a subscription inside [windowStart, windowEnd].
 */
function generateOccurrences(sub, windowStart, windowEnd, today) {
  validateSubscription(sub);
  d.parseDate(windowStart);
  d.parseDate(windowEnd);
  d.parseDate(today);

  var end = generationEnd(sub, today, windowEnd);
  var out = [];
  if (d.compareDate(sub.anchor_date, end) > 0) return out;

  var n = firstCandidateIndex(sub, windowStart);
  var guard = 0;
  while (guard++ < MAX_ITERATIONS) {
    var date = occurrenceAt(sub, n);
    if (d.compareDate(date, end) > 0) break;
    if (d.compareDate(date, windowStart) >= 0) out.push(date);
    n++;
  }
  if (guard >= MAX_ITERATIONS) {
    throw new Error("occurrence generation did not terminate for subscription " + sub.id);
  }
  return out;
}

/**
 * The price in force on a given date: the latest period starting on or before it.
 *
 * Charges that predate every recorded price fall back to the earliest one. That
 * is the forgiving reading of a user who added a price period later than the
 * anchor date, and it beats silently dropping charges from the history.
 */
function resolvePriceAt(pricePeriods, dateStr) {
  if (!pricePeriods || pricePeriods.length === 0) return null;
  var sorted = pricePeriods.slice().sort(function (a, b) {
    return d.compareDate(a.effective_from, b.effective_from);
  });
  var chosen = null;
  for (var i = 0; i < sorted.length; i++) {
    if (d.compareDate(sorted[i].effective_from, dateStr) <= 0) {
      chosen = sorted[i];
    } else {
      break;
    }
  }
  return chosen ? chosen : sorted[0];
}

/**
 * Whether the engine is allowed to overwrite this row.
 *
 * Anything the user or an importer has touched is theirs: an explicit skip, a
 * refund, an overridden amount, or a row that came from email rather than from
 * generation. Regeneration must never quietly rewrite recorded history.
 */
function isEngineOwned(charge) {
  if (charge.status !== "projected") return false;
  if (charge.has_override) return false;
  if (charge.source && charge.source !== "generated") return false;
  return true;
}

/**
 * Diff the schedule against stored charges.
 *
 * Returns actions rather than performing them, so the same function is used by
 * the PocketBase cron hook and by the tests, and so a dry run is free.
 */
function planCharges(input) {
  var sub = input.subscription;
  var periods = input.price_periods || [];
  var existing = input.existing_charges || [];
  var today = input.today;

  d.parseDate(today);
  if (periods.length === 0) {
    throw new Error("subscription " + sub.id + " has no price periods");
  }
  for (var p = 0; p < periods.length; p++) {
    d.parseDate(periods[p].effective_from);
  }

  var monthsBack = input.months_back === undefined ? DEFAULT_MONTHS_BACK : input.months_back;
  var monthsForward =
    input.months_forward === undefined ? DEFAULT_MONTHS_FORWARD : input.months_forward;
  var windowStart = input.window_start ? input.window_start : d.addMonths(today, -monthsBack);
  var windowEnd = input.window_end ? input.window_end : d.addMonths(today, monthsForward);

  var dates = generateOccurrences(sub, windowStart, windowEnd, today);

  var byDate = {};
  for (var i = 0; i < existing.length; i++) {
    byDate[existing[i].due_date] = existing[i];
  }

  var create = [];
  var update = [];
  var remove = [];
  var unchanged = 0;
  var preserved = 0;
  var seen = {};

  for (var j = 0; j < dates.length; j++) {
    var date = dates[j];
    seen[date] = true;
    var price = resolvePriceAt(periods, date);
    var row = byDate[date];

    if (!row) {
      create.push({
        subscription: sub.id,
        due_date: date,
        amount_minor: price.amount_minor,
        currency: sub.currency,
        status: "projected",
        source: "generated",
        has_override: false,
        external_ref: "",
      });
      continue;
    }

    if (!isEngineOwned(row)) {
      preserved++;
      continue;
    }

    if (row.amount_minor !== price.amount_minor || row.currency !== sub.currency) {
      update.push({
        id: row.id,
        amount_minor: price.amount_minor,
        currency: sub.currency,
      });
    } else {
      unchanged++;
    }
  }

  // Charges the schedule no longer produces: the anchor moved, the cycle
  // changed, or the subscription was cancelled with an earlier end_date.
  for (var k = 0; k < existing.length; k++) {
    var old = existing[k];
    if (seen[old.due_date]) continue;
    if (isEngineOwned(old)) {
      remove.push({ id: old.id, due_date: old.due_date });
    } else {
      preserved++;
    }
  }

  return {
    create: create,
    update: update,
    remove: remove,
    unchanged: unchanged,
    preserved: preserved,
    window_start: windowStart,
    window_end: windowEnd,
  };
}

/**
 * A charge's effective amount: the override when the user recorded one,
 * otherwise the generated amount. Skipped charges cost nothing.
 */
function effectiveAmountMinor(charge) {
  if (charge.status === "skipped") return 0;
  if (charge.has_override) return charge.actual_amount_minor;
  return charge.amount_minor;
}

/**
 * Normalised monthly cost across subscriptions, priced as at `asOf`.
 * Cancelled and paused subscriptions contribute nothing.
 */
function runRateMinor(subscriptions, pricePeriodsBySub, asOf) {
  var total = 0;
  for (var i = 0; i < subscriptions.length; i++) {
    var sub = subscriptions[i];
    if (sub.status !== "active" && sub.status !== "trial") continue;
    if (sub.end_date && d.compareDate(sub.end_date, asOf) < 0) continue;
    var periods = pricePeriodsBySub[sub.id] || [];
    var price = resolvePriceAt(periods, asOf);
    if (!price) continue;
    total += money.monthlyRunRateMinor(price.amount_minor, sub.cycle_unit, sub.cycle_count);
  }
  return total;
}

module.exports = {
  DEFAULT_MONTHS_BACK: DEFAULT_MONTHS_BACK,
  DEFAULT_MONTHS_FORWARD: DEFAULT_MONTHS_FORWARD,
  SUBSCRIPTION_STATUSES: SUBSCRIPTION_STATUSES,
  CHARGE_STATUSES: CHARGE_STATUSES,
  occurrenceAt: occurrenceAt,
  generationEnd: generationEnd,
  validateSubscription: validateSubscription,
  generateOccurrences: generateOccurrences,
  resolvePriceAt: resolvePriceAt,
  isEngineOwned: isEngineOwned,
  planCharges: planCharges,
  effectiveAmountMinor: effectiveAmountMinor,
  runRateMinor: runRateMinor,
};
