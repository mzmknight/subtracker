"use strict";

/**
 * Generates the shared projection corpus.
 *
 * There are now two implementations of the billing logic — engine.js on the
 * server and Projection.kt on the devices — because devices have to compute
 * their own schedules with no network. Two implementations drift. The guard is
 * this file: the JS engine (the older, more heavily tested one) produces a
 * corpus of cases with expected outputs, and *both* test suites assert against
 * it. A divergence fails a build instead of quietly mispricing someone's year.
 *
 * Writes two artefacts from one source of truth:
 *   backend/test/vectors.json                        — read by the JS suite
 *   apps/.../core/ProjectionVectors.kt               — embedded for the KMP suite
 *
 * Regenerate with: npm run vectors
 */

var fs = require("node:fs");
var path = require("node:path");
var engine = require("../engine/engine.js");

var root = path.join(__dirname, "..");
var jsonOut = path.join(root, "test", "vectors.json");
var kotlinOut = path.join(
  root,
  "..",
  "apps",
  "shared",
  "src",
  "commonTest",
  "kotlin",
  "io",
  "github",
  "mzmknight",
  "subtracker",
  "core",
  "ProjectionVectors.kt"
);

/** Deterministic LCG, so the corpus is stable across runs and reviewable in diffs. */
function seededRandom(seed) {
  var state = seed;
  return function () {
    state = (state * 1103515245 + 12345) % 2147483648;
    return state / 2147483648;
  };
}

function subscription(overrides) {
  var base = {
    id: "vector",
    name: "Vector",
    status: "active",
    currency: "GBP",
    cycle_unit: "month",
    cycle_count: 1,
    anchor_date: "2026-01-15",
    end_date: null,
  };
  for (var key in overrides) base[key] = overrides[key];
  return base;
}

function price(effective_from, amount_minor) {
  return { effective_from: effective_from, amount_minor: amount_minor };
}

var cases = [];

function addCase(name, sub, prices, today, windowStart, windowEnd) {
  var dates = engine.generateOccurrences(sub, windowStart, windowEnd, today);
  var expected = dates.map(function (due) {
    return { due: due, amount_minor: engine.resolvePriceAt(prices, due).amount_minor };
  });
  cases.push({
    name: name,
    subscription: sub,
    prices: prices,
    today: today,
    window_start: windowStart,
    window_end: windowEnd,
    expected: expected,
  });
}

// ---------------------------------------------------------- month-end clamping
addCase(
  "monthly anchored on the 31st does not drift",
  subscription({ anchor_date: "2026-01-31" }),
  [price("2026-01-31", 1099)],
  "2026-08-09", "2026-01-01", "2027-06-30"
);
addCase(
  "monthly anchored on the 30th",
  subscription({ anchor_date: "2026-01-30" }),
  [price("2026-01-30", 500)],
  "2026-08-09", "2026-01-01", "2027-01-31"
);
addCase(
  "monthly anchored on the 29th crosses a non-leap February",
  subscription({ anchor_date: "2026-01-29" }),
  [price("2026-01-29", 500)],
  "2026-08-09", "2026-01-01", "2026-06-30"
);
addCase(
  "monthly mid-month is unaffected by clamping",
  subscription({ anchor_date: "2026-01-15" }),
  [price("2026-01-15", 999)],
  "2026-08-09", "2026-01-01", "2026-12-31"
);

// ---------------------------------------------------------- leap years
addCase(
  "yearly on 29 February returns to the 29th in the next leap year",
  subscription({ anchor_date: "2024-02-29", cycle_unit: "year", cycle_count: 1 }),
  [price("2024-02-29", 9500)],
  "2026-08-09", "2024-01-01", "2033-12-31"
);
addCase(
  "yearly on 1 January",
  subscription({ anchor_date: "2020-01-01", cycle_unit: "year", cycle_count: 1 }),
  [price("2020-01-01", 12000)],
  "2026-08-09", "2024-01-01", "2029-12-31"
);
addCase(
  "every two years",
  subscription({ anchor_date: "2021-03-31", cycle_unit: "year", cycle_count: 2 }),
  [price("2021-03-31", 4000)],
  "2026-08-09", "2021-01-01", "2031-12-31"
);

// ---------------------------------------------------------- other cycles
addCase(
  "quarterly keeps the anchor day across clamped quarters",
  subscription({ anchor_date: "2026-01-31", cycle_unit: "month", cycle_count: 3 }),
  [price("2026-01-31", 3600)],
  "2026-08-09", "2026-01-01", "2028-12-31"
);
addCase(
  "twice a year",
  subscription({ anchor_date: "2025-08-31", cycle_unit: "month", cycle_count: 6 }),
  [price("2025-08-31", 7000)],
  "2026-08-09", "2025-01-01", "2029-12-31"
);
addCase(
  "weekly",
  subscription({ anchor_date: "2026-01-01", cycle_unit: "week", cycle_count: 1 }),
  [price("2026-01-01", 250)],
  "2026-08-09", "2026-01-01", "2026-04-30"
);
addCase(
  "fortnightly",
  subscription({ anchor_date: "2026-01-01", cycle_unit: "week", cycle_count: 2 }),
  [price("2026-01-01", 400)],
  "2026-08-09", "2026-01-01", "2026-12-31"
);
addCase(
  "every ten days",
  subscription({ anchor_date: "2026-01-01", cycle_unit: "day", cycle_count: 10 }),
  [price("2026-01-01", 120)],
  "2026-08-09", "2026-01-01", "2026-06-30"
);
addCase(
  "daily",
  subscription({ anchor_date: "2026-07-01", cycle_unit: "day", cycle_count: 1 }),
  [price("2026-07-01", 15)],
  "2026-08-09", "2026-07-01", "2026-08-31"
);

// ---------------------------------------------------------- lifecycle
addCase(
  "cancellation truncates at end_date",
  subscription({ anchor_date: "2025-06-10", status: "cancelled", end_date: "2026-02-01" }),
  [price("2025-06-10", 4500)],
  "2026-08-09", "2025-01-01", "2027-12-31"
);
addCase(
  "pausing keeps history but forecasts nothing",
  subscription({ anchor_date: "2026-01-15", status: "paused" }),
  [price("2026-01-15", 800)],
  "2026-04-20", "2026-01-01", "2026-12-31"
);
addCase(
  "a trial bills normally",
  subscription({ anchor_date: "2026-01-15", status: "trial" }),
  [price("2026-01-15", 0)],
  "2026-01-20", "2026-01-01", "2026-06-30"
);
addCase(
  "end_date on the anchor itself yields a single charge",
  subscription({ anchor_date: "2026-03-01", end_date: "2026-03-01" }),
  [price("2026-03-01", 700)],
  "2026-08-09", "2026-01-01", "2026-12-31"
);
addCase(
  "cancelled with no end_date stops at today",
  subscription({ anchor_date: "2026-01-15", status: "cancelled" }),
  [price("2026-01-15", 1200)],
  "2026-05-20", "2026-01-01", "2026-12-31"
);

// ---------------------------------------------------------- pricing
addCase(
  "a mid-history price rise applies from its effective date",
  subscription({ anchor_date: "2026-01-15" }),
  [price("2026-01-15", 1199), price("2026-07-01", 1299)],
  "2026-08-09", "2026-01-01", "2027-06-30"
);
addCase(
  "several price changes",
  subscription({ anchor_date: "2025-01-10" }),
  [price("2025-01-10", 500), price("2025-06-01", 600), price("2026-01-01", 750), price("2026-09-01", 900)],
  "2026-08-09", "2025-01-01", "2027-12-31"
);
addCase(
  "charges predating every price use the earliest one",
  subscription({ anchor_date: "2025-01-10" }),
  [price("2026-01-01", 1000)],
  "2026-08-09", "2025-01-01", "2026-12-31"
);
addCase(
  "unsorted price periods resolve identically",
  subscription({ anchor_date: "2026-01-15" }),
  [price("2026-07-01", 1299), price("2026-01-15", 1199)],
  "2026-08-09", "2026-01-01", "2026-12-31"
);

// ---------------------------------------------------------- window handling
addCase(
  "a long history fast-forwards into the window",
  subscription({ anchor_date: "2010-01-31" }),
  [price("2010-01-31", 300)],
  "2026-08-09", "2026-01-01", "2026-12-31"
);
addCase(
  "a decade-old daily subscription",
  subscription({ anchor_date: "2012-03-15", cycle_unit: "day", cycle_count: 17 }),
  [price("2012-03-15", 60)],
  "2026-08-09", "2026-08-01", "2026-09-30"
);
addCase(
  "an anchor after the window start",
  subscription({ anchor_date: "2026-06-15" }),
  [price("2026-06-15", 1099)],
  "2026-08-09", "2026-01-01", "2026-12-31"
);
addCase(
  "an anchor beyond the window end yields nothing",
  subscription({ anchor_date: "2030-01-01" }),
  [price("2030-01-01", 1099)],
  "2026-08-09", "2026-01-01", "2026-12-31"
);

// ---------------------------------------------------------- randomised breadth
var random = seededRandom(20260809);
var units = ["day", "week", "month", "year"];
var statuses = ["active", "active", "active", "trial", "paused", "cancelled"];

for (var i = 0; i < 40; i++) {
  var unit = units[Math.floor(random() * units.length)];
  var count = 1 + Math.floor(random() * (unit === "day" ? 30 : 6));
  var year = 2019 + Math.floor(random() * 8);
  var month = 1 + Math.floor(random() * 12);
  var day = 1 + Math.floor(random() * 31);
  // Clamp to a real date rather than skipping, so month ends stay represented.
  var maxDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  var anchor =
    year + "-" + String(month).padStart(2, "0") + "-" + String(Math.min(day, maxDay)).padStart(2, "0");

  var status = statuses[Math.floor(random() * statuses.length)];
  var endDate = null;
  if (status === "cancelled" && random() > 0.3) {
    endDate = 2026 + "-" + String(1 + Math.floor(random() * 12)).padStart(2, "0") + "-15";
    if (endDate < anchor) endDate = null;
  }

  var priceCount = 1 + Math.floor(random() * 3);
  var prices = [price(anchor, 100 + Math.floor(random() * 5000))];
  for (var p = 1; p < priceCount; p++) {
    var pYear = 2024 + Math.floor(random() * 3);
    var pMonth = 1 + Math.floor(random() * 12);
    prices.push(
      price(pYear + "-" + String(pMonth).padStart(2, "0") + "-01", 100 + Math.floor(random() * 5000))
    );
  }

  addCase(
    "randomised #" + (i + 1) + " " + unit + "/" + count + " from " + anchor + " (" + status + ")",
    subscription({ anchor_date: anchor, cycle_unit: unit, cycle_count: count, status: status, end_date: endDate }),
    prices,
    "2026-08-09",
    "2026-01-01",
    "2027-12-31"
  );
}

var corpus = {
  note: "GENERATED by backend/scripts/generate-vectors.js from engine.js. Do not edit by hand.",
  generatedCases: cases.length,
  cases: cases,
};

var json = JSON.stringify(corpus, null, 2);
fs.writeFileSync(jsonOut, json + "\n");

/**
 * A JVM class file caps any single string constant at 64 KB of UTF-8, and the
 * corpus is larger than that, so it is emitted compact and in chunks that are
 * joined at runtime. Chunks are never allowed to end on a double quote — that
 * would run into the closing delimiter of the raw string and fail to parse.
 */
function splitForKotlin(text, size) {
  var parts = [];
  var index = 0;
  while (index < text.length) {
    var end = Math.min(index + size, text.length);
    while (end > index + 1 && end < text.length && text.charAt(end - 1) === '"') end--;
    parts.push(text.slice(index, end));
    index = end;
  }
  return parts;
}

var compact = JSON.stringify(corpus);
var chunks = splitForKotlin(compact, 30000).map(function (chunk) {
  return '    """' + chunk.replace(/\$/g, "${'$'}") + '""",';
});

var kotlin =
  "package io.github.mzmknight.subtracker.core\n" +
  "\n" +
  "// GENERATED FILE - do not edit.\n" +
  "// Source: backend/scripts/generate-vectors.js, produced from backend/engine/engine.js.\n" +
  "// Regenerate with: npm run vectors  (in backend/)\n" +
  "//\n" +
  "// The same corpus is asserted by the JS suite and by ProjectionVectorTest, so the\n" +
  "// server engine and the on-device engine cannot diverge without a build failing.\n" +
  "//\n" +
  "// Split into chunks joined at runtime: a single string constant cannot exceed\n" +
  "// 64 KB of UTF-8 in a class file, and this corpus is bigger.\n" +
  "private val PROJECTION_VECTOR_PARTS: List<String> = listOf(\n" +
  chunks.join("\n") +
  "\n)\n" +
  "\n" +
  "internal val PROJECTION_VECTORS_JSON: String = PROJECTION_VECTOR_PARTS.joinToString(\"\")\n";

fs.mkdirSync(path.dirname(kotlinOut), { recursive: true });
fs.writeFileSync(kotlinOut, kotlin);

var totalCharges = cases.reduce(function (sum, c) {
  return sum + c.expected.length;
}, 0);

console.log(
  "wrote " + cases.length + " cases (" + totalCharges + " expected charges)\n" +
    "  " + path.relative(process.cwd(), jsonOut) + "\n" +
    "  " + path.relative(process.cwd(), kotlinOut)
);
