"use strict";

/**
 * Money is always an integer number of minor units (pence). Nothing in this
 * codebase stores a decimal amount: 0.1 + 0.2 is not 0.3 in float64, and a
 * few hundred summed subscription charges drift visibly.
 *
 * Division only happens at the very edge, in formatMinor().
 */

var CYCLE_UNITS = ["day", "week", "month", "year"];

/** Mean Gregorian year, used only for day/week cycles where no exact answer exists. */
var DAYS_PER_YEAR = 365.25;

function assertCycle(unit, count) {
  if (CYCLE_UNITS.indexOf(unit) === -1) {
    throw new Error(
      'cycle_unit must be one of ' + CYCLE_UNITS.join("/") + ', got "' + unit + '"'
    );
  }
  if (!isFinite(count) || Math.floor(count) !== count || count < 1) {
    throw new Error("cycle_count must be a positive integer, got " + count);
  }
}

/**
 * How many times a cycle bills in a year.
 * Exact for month and year cycles; approximate for day and week.
 */
function chargesPerYear(unit, count) {
  assertCycle(unit, count);
  switch (unit) {
    case "day":
      return DAYS_PER_YEAR / count;
    case "week":
      return DAYS_PER_YEAR / (7 * count);
    case "month":
      return 12 / count;
    case "year":
      return 1 / count;
  }
}

/**
 * Normalised monthly cost — the "run rate".
 *
 * This is NOT what leaves your account this month; it is what the subscription
 * averages out to. A £120/year subscription is £10/month here and £0 in eleven
 * months out of twelve on the cash-flow view. Both numbers are shown, labelled
 * differently, because neither answers the other's question.
 */
function monthlyRunRateMinor(amountMinor, unit, count) {
  return Math.round((amountMinor * chargesPerYear(unit, count)) / 12);
}

function annualRunRateMinor(amountMinor, unit, count) {
  return Math.round(amountMinor * chargesPerYear(unit, count));
}

function sumMinor(values) {
  var total = 0;
  for (var i = 0; i < values.length; i++) {
    total += values[i];
  }
  return total;
}

var ZERO_DECIMAL_CURRENCIES = ["JPY", "KRW", "VND", "CLP", "ISK"];

/** Mirrors Money.SYMBOLS on the client. */
var SYMBOLS = {
  GBP: "£",
  EUR: "€",
  USD: "$",
  JPY: "¥",
  AUD: "A$",
  CAD: "C$",
  CHF: "CHF ",
  SEK: "kr ",
};

function symbol(currency) {
  return SYMBOLS[currency] || currency + " ";
}

function minorUnitDigits(currency) {
  return ZERO_DECIMAL_CURRENCIES.indexOf(currency) === -1 ? 2 : 0;
}

/** 1299, "GBP" -> "12.99". Presentation only; never feed this back into maths. */
function formatMinor(amountMinor, currency) {
  var digits = minorUnitDigits(currency);
  if (digits === 0) return "" + amountMinor;
  var negative = amountMinor < 0;
  var abs = Math.abs(amountMinor);
  var divisor = Math.pow(10, digits);
  var whole = Math.floor(abs / divisor);
  var frac = "" + (abs % divisor);
  while (frac.length < digits) frac = "0" + frac;
  return (negative ? "-" : "") + whole + "." + frac;
}

/**
 * 1299, "GBP" -> "£12.99". Use this anywhere a person reads the number —
 * a notification saying "12.99" makes the reader work out the currency.
 */
function format(amountMinor, currency) {
  return symbol(currency) + formatMinor(amountMinor, currency);
}

/** "12.99", "GBP" -> 1299. Used when parsing user input on the way in. */
function parseMinor(text, currency) {
  var digits = minorUnitDigits(currency);
  var trimmed = ("" + text).trim().replace(/,/g, "");
  if (!/^-?\d*(\.\d+)?$/.test(trimmed) || trimmed === "" || trimmed === "-") {
    throw new Error('cannot parse "' + text + '" as an amount');
  }
  var negative = trimmed.charAt(0) === "-";
  var body = negative ? trimmed.slice(1) : trimmed;
  var parts = body.split(".");
  var whole = parts[0] === "" ? "0" : parts[0];
  var frac = parts.length > 1 ? parts[1] : "";
  if (frac.length > digits) {
    throw new Error(
      currency + " has " + digits + " minor digits, but got " + frac.length + ' in "' + text + '"'
    );
  }
  while (frac.length < digits) frac = frac + "0";
  var value = parseInt(whole, 10) * Math.pow(10, digits) + (digits ? parseInt(frac, 10) : 0);
  return negative ? -value : value;
}

module.exports = {
  CYCLE_UNITS: CYCLE_UNITS,
  DAYS_PER_YEAR: DAYS_PER_YEAR,
  assertCycle: assertCycle,
  chargesPerYear: chargesPerYear,
  monthlyRunRateMinor: monthlyRunRateMinor,
  annualRunRateMinor: annualRunRateMinor,
  sumMinor: sumMinor,
  minorUnitDigits: minorUnitDigits,
  symbol: symbol,
  format: format,
  formatMinor: formatMinor,
  parseMinor: parseMinor,
};
