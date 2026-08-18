"use strict";

/**
 * The server half of the shared-corpus check.
 *
 * The corpus is generated *from* this engine, so on its own this only catches
 * regressions here. Its real value is as the other half of a pair: the identical
 * cases and expected answers are asserted by ProjectionVectorTest on the Kotlin
 * side, which is what stops the on-device engine drifting from this one.
 */

var test = require("node:test");
var assert = require("node:assert");
var fs = require("node:fs");
var path = require("node:path");

var engine = require("../engine/engine.js");

var corpus = JSON.parse(fs.readFileSync(path.join(__dirname, "vectors.json"), "utf8"));

test("the corpus is present and covers the hard cases", function () {
  assert.ok(corpus.cases.length >= 60, "expected a broad corpus, got " + corpus.cases.length);
  var names = corpus.cases.map(function (c) { return c.name; });
  assert.ok(names.some(function (n) { return n.indexOf("31st") !== -1; }));
  assert.ok(names.some(function (n) { return n.indexOf("29 February") !== -1; }));
  assert.ok(names.some(function (n) { return n.indexOf("price rise") !== -1; }));
  assert.ok(names.some(function (n) { return n.indexOf("cancellation") !== -1; }));
});

test("the engine still reproduces every case in the corpus", function () {
  var failures = [];

  corpus.cases.forEach(function (testCase) {
    var dates = engine.generateOccurrences(
      testCase.subscription,
      testCase.window_start,
      testCase.window_end,
      testCase.today
    );
    var produced = dates.map(function (due) {
      return { due: due, amount_minor: engine.resolvePriceAt(testCase.prices, due).amount_minor };
    });

    if (JSON.stringify(produced) !== JSON.stringify(testCase.expected)) {
      failures.push(testCase.name);
    }
  });

  assert.deepEqual(
    failures,
    [],
    "engine no longer matches the committed corpus — regenerate deliberately with `npm run vectors` " +
      "and check the diff, do not regenerate to make this pass"
  );
});

test("the corpus is stale-proof: regenerating produces the same answers", function () {
  // Guards against a corpus committed from a broken engine and never rechecked.
  corpus.cases.forEach(function (testCase) {
    testCase.expected.forEach(function (entry) {
      var resolved = engine.resolvePriceAt(testCase.prices, entry.due);
      assert.equal(
        resolved.amount_minor,
        entry.amount_minor,
        testCase.name + " @ " + entry.due + ": price resolution changed"
      );
    });
  });
});
