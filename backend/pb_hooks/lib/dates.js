// GENERATED FILE — do not edit.
// Source: backend/engine/dates.js
// Regenerate with: npm run sync-hooks

"use strict";

/**
 * Plain-calendar-date arithmetic on "YYYY-MM-DD" strings.
 *
 * Deliberately does NOT use the JS Date object. A billing date is a calendar
 * date, not an instant: "1 August" must mean 1 August regardless of the host
 * timezone. Date-based arithmetic silently shifts by a day either side of UTC
 * and the bug only shows up for some users, in some months.
 *
 * Everything here is pure integer maths and is safe under PocketBase's goja
 * runtime (no ESM, no optional chaining, no nullish coalescing).
 */

var MONTH_LENGTHS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
var DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

function isLeapYear(y) {
  return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
}

function daysInMonth(y, m) {
  if (m === 2 && isLeapYear(y)) return 29;
  return MONTH_LENGTHS[m - 1];
}

/** "2026-08-09" -> { y: 2026, m: 8, d: 9 }. Throws on anything malformed. */
function parseDate(s) {
  if (typeof s !== "string") {
    throw new Error("date must be a string, got " + typeof s);
  }
  var match = DATE_RE.exec(s);
  if (!match) {
    throw new Error('date must be formatted YYYY-MM-DD, got "' + s + '"');
  }
  var y = parseInt(match[1], 10);
  var m = parseInt(match[2], 10);
  var d = parseInt(match[3], 10);
  if (m < 1 || m > 12) {
    throw new Error('month out of range in "' + s + '"');
  }
  if (d < 1 || d > daysInMonth(y, m)) {
    throw new Error('day out of range in "' + s + '"');
  }
  return { y: y, m: m, d: d };
}

function pad2(n) {
  return n < 10 ? "0" + n : "" + n;
}

function pad4(n) {
  var s = "" + n;
  while (s.length < 4) s = "0" + s;
  return s;
}

/** { y, m, d } -> "YYYY-MM-DD". */
function formatDate(parts) {
  return pad4(parts.y) + "-" + pad2(parts.m) + "-" + pad2(parts.d);
}

/**
 * Days since the epoch, by Howard Hinnant's proleptic Gregorian algorithm.
 * Used only for day/week stepping; month and year stepping is done in month
 * space so that day-of-month clamping behaves correctly.
 */
function daysFromCivil(y, m, d) {
  var yy = y - (m <= 2 ? 1 : 0);
  var era = Math.floor(yy / 400);
  var yoe = yy - era * 400;
  var doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  var doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

function civilFromDays(z) {
  var zz = z + 719468;
  var era = Math.floor(zz / 146097);
  var doe = zz - era * 146097;
  var yoe = Math.floor(
    (doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365
  );
  var y = yoe + era * 400;
  var doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  var mp = Math.floor((5 * doy + 2) / 153);
  var d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  var m = mp + (mp < 10 ? 3 : -9);
  return { y: y + (m <= 2 ? 1 : 0), m: m, d: d };
}

function toEpochDay(dateStr) {
  var p = parseDate(dateStr);
  return daysFromCivil(p.y, p.m, p.d);
}

function fromEpochDay(n) {
  return formatDate(civilFromDays(n));
}

function addDays(dateStr, n) {
  return fromEpochDay(toEpochDay(dateStr) + n);
}

/**
 * Add n months, clamping the day of month to the target month's last day.
 * 2026-01-31 + 1 month is 2026-02-28, not 2026-03-03.
 */
function addMonths(dateStr, n) {
  var p = parseDate(dateStr);
  var totalMonths = p.y * 12 + (p.m - 1) + n;
  var y = Math.floor(totalMonths / 12);
  var m = (((totalMonths % 12) + 12) % 12) + 1;
  var d = Math.min(p.d, daysInMonth(y, m));
  return formatDate({ y: y, m: m, d: d });
}

/** Lexicographic comparison is chronological for zero-padded ISO dates. */
function compareDate(a, b) {
  if (a < b) return -1;
  if (a > b) return 1;
  return 0;
}

function minDate(a, b) {
  return compareDate(a, b) <= 0 ? a : b;
}

function maxDate(a, b) {
  return compareDate(a, b) >= 0 ? a : b;
}

/** Whole months between two dates, rounded down. Used for run-rate maths. */
function monthsBetween(fromStr, toStr) {
  var a = parseDate(fromStr);
  var b = parseDate(toStr);
  var months = (b.y - a.y) * 12 + (b.m - a.m);
  if (b.d < a.d) months -= 1;
  return months;
}

/** "2026-08" — the grouping key used by the monthly aggregate views. */
function monthKey(dateStr) {
  return dateStr.slice(0, 7);
}

var MONTH_NAMES = [
  "Jan", "Feb", "Mar", "Apr", "May", "Jun",
  "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

/** "2026-08-11" -> "11 Aug". For anything a person reads. */
function formatShort(dateStr) {
  var p = parseDate(dateStr);
  return p.d + " " + MONTH_NAMES[p.m - 1];
}

/** "2026-08-11" -> "11 Aug 2026". */
function formatLong(dateStr) {
  var p = parseDate(dateStr);
  return p.d + " " + MONTH_NAMES[p.m - 1] + " " + p.y;
}

/**
 * "today", "tomorrow", "in 3 days" — a reminder that says "2026-08-11" makes
 * the reader do arithmetic to work out whether it matters.
 */
function describeDue(dueStr, todayStr) {
  var days = toEpochDay(dueStr) - toEpochDay(todayStr);
  if (days < 0) return "overdue";
  if (days === 0) return "today";
  if (days === 1) return "tomorrow";
  return "in " + days + " days";
}

module.exports = {
  isLeapYear: isLeapYear,
  daysInMonth: daysInMonth,
  parseDate: parseDate,
  formatDate: formatDate,
  daysFromCivil: daysFromCivil,
  civilFromDays: civilFromDays,
  toEpochDay: toEpochDay,
  fromEpochDay: fromEpochDay,
  addDays: addDays,
  addMonths: addMonths,
  compareDate: compareDate,
  minDate: minDate,
  maxDate: maxDate,
  monthsBetween: monthsBetween,
  monthKey: monthKey,
  formatShort: formatShort,
  formatLong: formatLong,
  describeDue: describeDue,
};
