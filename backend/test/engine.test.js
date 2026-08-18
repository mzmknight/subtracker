"use strict";

var test = require("node:test");
var assert = require("node:assert");

var d = require("../engine/dates.js");
var money = require("../engine/money.js");
var engine = require("../engine/engine.js");

// ---------------------------------------------------------------- helpers

function sub(overrides) {
  var base = {
    id: "sub1",
    name: "Test",
    status: "active",
    currency: "GBP",
    cycle_unit: "month",
    cycle_count: 1,
    anchor_date: "2026-01-15",
    end_date: null,
  };
  for (var k in overrides) base[k] = overrides[k];
  return base;
}

function price(effective_from, amount_minor) {
  return { id: "p" + effective_from, effective_from: effective_from, amount_minor: amount_minor };
}

/** Walks from n = 0 with no fast-forward — the obviously-correct reference. */
function naiveOccurrences(s, windowStart, windowEnd, today) {
  var end = engine.generationEnd(s, today, windowEnd);
  var out = [];
  for (var n = 0; n < 20000; n++) {
    var date = engine.occurrenceAt(s, n);
    if (d.compareDate(date, end) > 0) break;
    if (d.compareDate(date, windowStart) >= 0) out.push(date);
  }
  return out;
}

/** Applies a plan to an in-memory charge list, mimicking what the hook does. */
function applyPlan(existing, plan) {
  var next = existing.slice();
  var removed = {};
  for (var i = 0; i < plan.remove.length; i++) removed[plan.remove[i].id] = true;
  next = next.filter(function (row) {
    return !removed[row.id];
  });
  for (var j = 0; j < plan.update.length; j++) {
    var u = plan.update[j];
    for (var k = 0; k < next.length; k++) {
      if (next[k].id === u.id) {
        next[k].amount_minor = u.amount_minor;
        next[k].currency = u.currency;
      }
    }
  }
  for (var m = 0; m < plan.create.length; m++) {
    var row = plan.create[m];
    var copy = {};
    for (var f in row) copy[f] = row[f];
    copy.id = "gen-" + copy.due_date;
    next.push(copy);
  }
  return next;
}

// ---------------------------------------------------------------- dates

test("leap years follow the Gregorian rule", function () {
  assert.equal(d.isLeapYear(2024), true);
  assert.equal(d.isLeapYear(2026), false);
  assert.equal(d.isLeapYear(1900), false, "century years are not leap");
  assert.equal(d.isLeapYear(2000), true, "unless divisible by 400");
  assert.equal(d.daysInMonth(2024, 2), 29);
  assert.equal(d.daysInMonth(2026, 2), 28);
  assert.equal(d.daysInMonth(2026, 4), 30);
});

test("parseDate rejects malformed and impossible dates", function () {
  assert.throws(function () { d.parseDate("2026-2-9"); }, /YYYY-MM-DD/);
  assert.throws(function () { d.parseDate("09/08/2026"); }, /YYYY-MM-DD/);
  assert.throws(function () { d.parseDate("2026-13-01"); }, /month out of range/);
  assert.throws(function () { d.parseDate("2026-02-30"); }, /day out of range/);
  assert.throws(function () { d.parseDate("2026-02-29"); }, /day out of range/);
  assert.doesNotThrow(function () { d.parseDate("2024-02-29"); });
});

test("epoch day conversion round-trips across a 40-year span", function () {
  var start = d.toEpochDay("2000-01-01");
  var end = d.toEpochDay("2040-01-01");
  for (var n = start; n <= end; n++) {
    assert.equal(d.toEpochDay(d.fromEpochDay(n)), n);
  }
});

test("addDays crosses month, year and leap boundaries", function () {
  assert.equal(d.addDays("2026-01-31", 1), "2026-02-01");
  assert.equal(d.addDays("2026-12-31", 1), "2027-01-01");
  assert.equal(d.addDays("2024-02-28", 1), "2024-02-29");
  assert.equal(d.addDays("2026-02-28", 1), "2026-03-01");
  assert.equal(d.addDays("2026-03-01", -1), "2026-02-28");
});

test("addMonths clamps to the last day of a short month", function () {
  assert.equal(d.addMonths("2026-01-31", 1), "2026-02-28");
  assert.equal(d.addMonths("2028-01-31", 1), "2028-02-29");
  assert.equal(d.addMonths("2026-03-31", 1), "2026-04-30");
  assert.equal(d.addMonths("2026-01-15", 1), "2026-02-15");
  assert.equal(d.addMonths("2026-01-15", -1), "2025-12-15");
  assert.equal(d.addMonths("2026-01-31", 12), "2027-01-31");
});

// ---------------------------------------------------------------- occurrences

test("monthly billing anchored on the 31st never drifts", function () {
  var s = sub({ anchor_date: "2026-01-31" });
  var got = engine.generateOccurrences(s, "2026-01-01", "2027-02-28", "2026-01-01");
  assert.deepEqual(got, [
    "2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30",
    "2026-05-31", "2026-06-30", "2026-07-31", "2026-08-31",
    "2026-09-30", "2026-10-31", "2026-11-30", "2026-12-31",
    "2027-01-31", "2027-02-28",
  ]);
});

test("a clamped month does not shift the following month", function () {
  // The regression this whole design exists to prevent: stepping from the
  // previous occurrence would give 28 March here instead of 31 March.
  var s = sub({ anchor_date: "2026-01-31" });
  var got = engine.generateOccurrences(s, "2026-03-01", "2026-03-31", "2026-01-01");
  assert.deepEqual(got, ["2026-03-31"]);
});

test("yearly billing on 29 February returns to the 29th in the next leap year", function () {
  var s = sub({ anchor_date: "2024-02-29", cycle_unit: "year", cycle_count: 1 });
  var got = engine.generateOccurrences(s, "2024-01-01", "2028-12-31", "2024-01-01");
  assert.deepEqual(got, ["2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"]);
});

test("weekly, fortnightly and every-N-day cycles", function () {
  var weekly = engine.generateOccurrences(
    sub({ anchor_date: "2026-01-01", cycle_unit: "week", cycle_count: 1 }),
    "2026-01-01", "2026-02-05", "2026-01-01"
  );
  assert.deepEqual(weekly, [
    "2026-01-01", "2026-01-08", "2026-01-15", "2026-01-22", "2026-01-29", "2026-02-05",
  ]);

  var fortnightly = engine.generateOccurrences(
    sub({ anchor_date: "2026-01-01", cycle_unit: "week", cycle_count: 2 }),
    "2026-01-01", "2026-02-28", "2026-01-01"
  );
  assert.deepEqual(fortnightly, ["2026-01-01", "2026-01-15", "2026-01-29", "2026-02-12", "2026-02-26"]);

  var tenDaily = engine.generateOccurrences(
    sub({ anchor_date: "2026-01-01", cycle_unit: "day", cycle_count: 10 }),
    "2026-01-01", "2026-02-10", "2026-01-01"
  );
  assert.deepEqual(tenDaily, ["2026-01-01", "2026-01-11", "2026-01-21", "2026-01-31", "2026-02-10"]);
});

test("quarterly billing keeps the anchor day across clamped quarters", function () {
  var s = sub({ anchor_date: "2026-01-31", cycle_unit: "month", cycle_count: 3 });
  var got = engine.generateOccurrences(s, "2026-01-01", "2027-01-31", "2026-01-01");
  assert.deepEqual(got, ["2026-01-31", "2026-04-30", "2026-07-31", "2026-10-31", "2027-01-31"]);
});

test("fast-forwarding into the window matches a naive walk from the anchor", function () {
  // Directly validates firstCandidateIndex, which is pure optimisation and
  // therefore the easiest thing here to get subtly wrong.
  var cases = [
    { cycle_unit: "month", cycle_count: 1, anchor_date: "2010-01-31" },
    { cycle_unit: "month", cycle_count: 3, anchor_date: "2011-05-30" },
    { cycle_unit: "year", cycle_count: 1, anchor_date: "2012-02-29" },
    { cycle_unit: "week", cycle_count: 2, anchor_date: "2013-07-04" },
    { cycle_unit: "day", cycle_count: 17, anchor_date: "2014-11-30" },
    { cycle_unit: "month", cycle_count: 6, anchor_date: "2015-08-31" },
  ];
  for (var i = 0; i < cases.length; i++) {
    var s = sub(cases[i]);
    var windowStart = "2026-01-01";
    var windowEnd = "2027-12-31";
    assert.deepEqual(
      engine.generateOccurrences(s, windowStart, windowEnd, "2026-08-09"),
      naiveOccurrences(s, windowStart, windowEnd, "2026-08-09"),
      "mismatch for " + JSON.stringify(cases[i])
    );
  }
});

test("cancellation truncates the schedule at end_date", function () {
  var s = sub({ anchor_date: "2026-01-15", status: "cancelled", end_date: "2026-04-01" });
  var got = engine.generateOccurrences(s, "2026-01-01", "2026-12-31", "2026-08-09");
  assert.deepEqual(got, ["2026-01-15", "2026-02-15", "2026-03-15"]);
});

test("pausing keeps history but generates nothing in the future", function () {
  var s = sub({ anchor_date: "2026-01-15", status: "paused" });
  var got = engine.generateOccurrences(s, "2026-01-01", "2026-12-31", "2026-04-20");
  assert.deepEqual(got, ["2026-01-15", "2026-02-15", "2026-03-15", "2026-04-15"]);
});

test("a trial subscription bills normally", function () {
  var s = sub({ anchor_date: "2026-01-15", status: "trial", trial_end: "2026-02-15" });
  var got = engine.generateOccurrences(s, "2026-01-01", "2026-03-31", "2026-01-20");
  assert.deepEqual(got, ["2026-01-15", "2026-02-15", "2026-03-15"]);
});

test("invalid subscriptions are rejected rather than silently skipped", function () {
  assert.throws(function () {
    engine.generateOccurrences(sub({ cycle_count: 0 }), "2026-01-01", "2026-12-31", "2026-01-01");
  }, /positive integer/);
  assert.throws(function () {
    engine.generateOccurrences(sub({ cycle_unit: "fortnight" }), "2026-01-01", "2026-12-31", "2026-01-01");
  }, /cycle_unit/);
  assert.throws(function () {
    engine.generateOccurrences(sub({ status: "archived" }), "2026-01-01", "2026-12-31", "2026-01-01");
  }, /status must be one of/);
  assert.throws(function () {
    engine.generateOccurrences(
      sub({ anchor_date: "2026-06-01", end_date: "2026-01-01" }),
      "2026-01-01", "2026-12-31", "2026-01-01"
    );
  }, /end_date is before anchor_date/);
});

// ---------------------------------------------------------------- pricing

test("each charge is valued at the price in force on its own due date", function () {
  var periods = [price("2026-01-01", 999), price("2026-06-01", 1299)];
  assert.equal(engine.resolvePriceAt(periods, "2026-05-31").amount_minor, 999);
  assert.equal(engine.resolvePriceAt(periods, "2026-06-01").amount_minor, 1299);
  assert.equal(engine.resolvePriceAt(periods, "2026-09-01").amount_minor, 1299);
});

test("price lookup is order-independent and falls back for early charges", function () {
  var unsorted = [price("2026-06-01", 1299), price("2026-01-01", 999)];
  assert.equal(engine.resolvePriceAt(unsorted, "2026-03-01").amount_minor, 999);
  assert.equal(
    engine.resolvePriceAt(unsorted, "2025-01-01").amount_minor,
    999,
    "charges predating every price period use the earliest known price"
  );
});

// ---------------------------------------------------------------- planning

test("planning against an empty table creates the whole schedule", function () {
  var plan = engine.planCharges({
    subscription: sub({ anchor_date: "2026-06-15" }),
    price_periods: [price("2026-06-15", 1099)],
    existing_charges: [],
    today: "2026-08-09",
    months_back: 3,
    months_forward: 3,
  });
  // Window is 2026-05-09 .. 2026-11-09, so the November charge falls outside it.
  assert.equal(plan.window_start, "2026-05-09");
  assert.equal(plan.window_end, "2026-11-09");
  assert.deepEqual(
    plan.create.map(function (c) { return c.due_date; }),
    ["2026-06-15", "2026-07-15", "2026-08-15", "2026-09-15", "2026-10-15"]
  );
  assert.equal(plan.create[0].amount_minor, 1099);
  assert.equal(plan.create[0].currency, "GBP");
  assert.equal(plan.create[0].status, "projected");
  assert.equal(plan.create[0].source, "generated");
  assert.equal(plan.remove.length, 0);
});

test("re-running the projection is a no-op", function () {
  var s = sub({ anchor_date: "2026-01-15" });
  var periods = [price("2026-01-15", 999)];
  var input = {
    subscription: s,
    price_periods: periods,
    existing_charges: [],
    today: "2026-08-09",
    months_back: 12,
    months_forward: 12,
  };

  var first = engine.planCharges(input);
  assert.ok(first.create.length > 0);
  var stored = applyPlan([], first);

  input.existing_charges = stored;
  var second = engine.planCharges(input);
  assert.equal(second.create.length, 0, "second run created rows");
  assert.equal(second.update.length, 0, "second run updated rows");
  assert.equal(second.remove.length, 0, "second run deleted rows");
  assert.equal(second.unchanged, stored.length);
});

test("a price rise updates future charges without rewriting settled ones", function () {
  var s = sub({ anchor_date: "2026-01-15" });
  var input = {
    subscription: s,
    price_periods: [price("2026-01-15", 999)],
    existing_charges: [],
    today: "2026-08-09",
    months_back: 12,
    months_forward: 6,
  };
  var stored = applyPlan([], engine.planCharges(input));

  // The user marks March as skipped and corrects April's amount by hand.
  for (var i = 0; i < stored.length; i++) {
    if (stored[i].due_date === "2026-03-15") stored[i].status = "skipped";
    if (stored[i].due_date === "2026-04-15") {
      stored[i].has_override = true;
      stored[i].actual_amount_minor = 500;
    }
  }

  input.existing_charges = stored;
  input.price_periods = [price("2026-01-15", 999), price("2026-09-01", 1299)];
  var plan = engine.planCharges(input);

  var updatedDates = plan.update.map(function (u) {
    for (var j = 0; j < stored.length; j++) if (stored[j].id === u.id) return stored[j].due_date;
  });
  // Window ends 2027-02-09, so the February charge is beyond the horizon.
  assert.equal(plan.window_end, "2027-02-09");
  assert.deepEqual(updatedDates, ["2026-09-15", "2026-10-15", "2026-11-15", "2026-12-15", "2027-01-15"]);
  assert.equal(plan.update[0].amount_minor, 1299);
  assert.equal(plan.preserved, 2, "the skipped and overridden rows were left alone");
  assert.equal(plan.remove.length, 0);
});

test("user-touched and imported rows are never overwritten or deleted", function () {
  var s = sub({ anchor_date: "2026-01-15" });
  var protectedRows = [
    { id: "c1", due_date: "2026-01-15", amount_minor: 1, currency: "GBP", status: "skipped", source: "generated", has_override: false },
    { id: "c2", due_date: "2026-02-15", amount_minor: 1, currency: "GBP", status: "refunded", source: "generated", has_override: false },
    { id: "c3", due_date: "2026-03-15", amount_minor: 1, currency: "GBP", status: "projected", source: "generated", has_override: true, actual_amount_minor: 42 },
    { id: "c4", due_date: "2026-04-15", amount_minor: 1, currency: "GBP", status: "projected", source: "email", has_override: false },
    // Not on the schedule at all — an import found a charge the engine never predicted.
    { id: "c5", due_date: "2026-04-17", amount_minor: 1, currency: "GBP", status: "projected", source: "email", has_override: false },
  ];

  var plan = engine.planCharges({
    subscription: s,
    price_periods: [price("2026-01-15", 999)],
    existing_charges: protectedRows,
    today: "2026-08-09",
    months_back: 12,
    months_forward: 0,
  });

  assert.equal(plan.update.length, 0, "protected rows must not be repriced");
  assert.equal(plan.remove.length, 0, "protected rows must not be deleted");
  assert.equal(plan.preserved, 5);
});

test("moving the anchor removes the charges the schedule no longer produces", function () {
  var input = {
    subscription: sub({ anchor_date: "2026-01-15" }),
    price_periods: [price("2026-01-15", 999)],
    existing_charges: [],
    today: "2026-08-09",
    months_back: 6,
    months_forward: 0,
  };
  var stored = applyPlan([], engine.planCharges(input));
  // Window is 2026-02-09 .. 2026-08-09.
  assert.deepEqual(
    stored.map(function (r) { return r.due_date; }),
    ["2026-02-15", "2026-03-15", "2026-04-15", "2026-05-15", "2026-06-15", "2026-07-15"]
  );

  input.subscription = sub({ anchor_date: "2026-01-20" });
  input.existing_charges = stored;
  var plan = engine.planCharges(input);

  assert.deepEqual(
    plan.remove.map(function (r) { return r.due_date; }).sort(),
    ["2026-02-15", "2026-03-15", "2026-04-15", "2026-05-15", "2026-06-15", "2026-07-15"]
  );
  assert.deepEqual(
    plan.create.map(function (c) { return c.due_date; }).sort(),
    ["2026-02-20", "2026-03-20", "2026-04-20", "2026-05-20", "2026-06-20", "2026-07-20"]
  );
});

test("a subscription with no recorded price is an error, not an empty schedule", function () {
  assert.throws(function () {
    engine.planCharges({
      subscription: sub({}),
      price_periods: [],
      existing_charges: [],
      today: "2026-08-09",
    });
  }, /no price periods/);
});

// ---------------------------------------------------------------- money

test("run rate normalises every cycle to a monthly figure", function () {
  assert.equal(money.monthlyRunRateMinor(1000, "month", 1), 1000);
  assert.equal(money.monthlyRunRateMinor(12000, "year", 1), 1000);
  assert.equal(money.monthlyRunRateMinor(3000, "month", 3), 1000);
  assert.equal(money.monthlyRunRateMinor(24000, "year", 2), 1000);
  // Weekly has no exact monthly equivalent; 365.25/7/12 weeks per month.
  assert.equal(money.monthlyRunRateMinor(1000, "week", 1), 4348);
  assert.equal(money.annualRunRateMinor(999, "month", 1), 11988);
});

test("amounts round-trip through minor units without float drift", function () {
  assert.equal(money.parseMinor("12.99", "GBP"), 1299);
  assert.equal(money.parseMinor("0.01", "GBP"), 1);
  assert.equal(money.parseMinor("100", "GBP"), 10000);
  assert.equal(money.parseMinor("1,299.50", "GBP"), 129950);
  assert.equal(money.formatMinor(1299, "GBP"), "12.99");
  assert.equal(money.formatMinor(5, "GBP"), "0.05");
  assert.equal(money.formatMinor(-1299, "GBP"), "-12.99");
  assert.equal(money.formatMinor(1000, "JPY"), "1000");
  assert.equal(money.parseMinor("1000", "JPY"), 1000);
  assert.throws(function () { money.parseMinor("12.999", "GBP"); }, /minor digits/);
  assert.throws(function () { money.parseMinor("free", "GBP"); }, /cannot parse/);

  // The reason integers are used at all: the float equivalent of this loop
  // lands on 100.00000000000133, and every dashboard total inherits the error.
  var total = 0;
  for (var i = 0; i < 1000; i++) total += money.parseMinor("0.10", "GBP");
  assert.equal(total, 10000);
  assert.equal(money.formatMinor(total, "GBP"), "100.00");

  var floatTotal = 0;
  for (var j = 0; j < 1000; j++) floatTotal += 0.1;
  assert.notEqual(floatTotal, 100);
});

test("amounts are formatted for a human to read", function () {
  // A notification saying "12.99" makes the reader work out the currency.
  assert.equal(money.symbol("GBP"), "£");
  assert.equal(money.format(1299, "GBP"), "£12.99");
  assert.equal(money.format(0, "GBP"), "£0.00");
  assert.equal(money.format(1000, "JPY"), "¥1000");
  assert.equal(money.format(500, "XYZ"), "XYZ 5.00", "an unknown currency still reads sensibly");
});

test("dates are described the way a person would say them", function () {
  assert.equal(d.formatShort("2026-08-11"), "11 Aug");
  assert.equal(d.formatLong("2026-08-11"), "11 Aug 2026");
  assert.equal(d.describeDue("2026-08-09", "2026-08-09"), "today");
  assert.equal(d.describeDue("2026-08-10", "2026-08-09"), "tomorrow");
  assert.equal(d.describeDue("2026-08-11", "2026-08-09"), "in 2 days");
  assert.equal(d.describeDue("2026-08-08", "2026-08-09"), "overdue");
  // Crossing a month boundary must count real days, not arithmetic on the day number.
  assert.equal(d.describeDue("2026-09-01", "2026-08-31"), "tomorrow");
  assert.equal(d.describeDue("2026-03-01", "2026-02-28"), "tomorrow");
});

test("effective amount honours overrides and skips", function () {
  assert.equal(engine.effectiveAmountMinor({ amount_minor: 999, status: "projected" }), 999);
  assert.equal(engine.effectiveAmountMinor({ amount_minor: 999, status: "skipped" }), 0);
  assert.equal(
    engine.effectiveAmountMinor({ amount_minor: 999, status: "projected", has_override: true, actual_amount_minor: 500 }),
    500
  );
});

test("run rate ignores paused, cancelled and expired subscriptions", function () {
  var subs = [
    sub({ id: "a", cycle_unit: "month", cycle_count: 1 }),
    sub({ id: "b", cycle_unit: "year", cycle_count: 1 }),
    sub({ id: "c", status: "paused" }),
    sub({ id: "d", status: "cancelled", end_date: "2026-01-01" }),
    sub({ id: "e", status: "active", end_date: "2026-03-01" }),
    sub({ id: "f", status: "trial" }),
  ];
  var periods = {
    a: [price("2026-01-01", 1000)],
    b: [price("2026-01-01", 12000)],
    c: [price("2026-01-01", 9900)],
    d: [price("2026-01-01", 9900)],
    e: [price("2026-01-01", 9900)],
    f: [price("2026-01-01", 500)],
  };
  assert.equal(engine.runRateMinor(subs, periods, "2026-08-09"), 1000 + 1000 + 500);
});
