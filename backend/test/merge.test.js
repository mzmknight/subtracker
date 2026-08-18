"use strict";

/**
 * The server's merge must behave identically to Merge.kt on the devices.
 * These assertions deliberately mirror MergeTest.kt case for case — if the two
 * diverge, syncing with the server produces a different answer from syncing
 * with a phone and nothing converges.
 */

var test = require("node:test");
var assert = require("node:assert");

var merge = require("../engine/merge.js");

function stamp(millis, device) {
  return (
    String(millis).padStart(16, "0") + "-" + "00000" + "-" + device.padEnd(16, "x")
  );
}

function note(id, at, device, extra) {
  var record = { id: id, updated_at: stamp(at, device), deleted: false };
  for (var key in extra) record[key] = extra[key];
  return record;
}

test("the later write wins", function () {
  var result = merge.merge(
    [note("n1", 100, "a", { text: "old" })],
    [note("n1", 200, "b", { text: "new" })]
  );
  assert.equal(result.merged.length, 1);
  assert.equal(result.merged[0].text, "new");
  assert.equal(result.incoming.length, 1);
});

test("the earlier write loses and is offered back to the peer", function () {
  var result = merge.merge(
    [note("n1", 300, "a", { text: "mine" })],
    [note("n1", 200, "b", { text: "theirs" })]
  );
  assert.equal(result.merged[0].text, "mine");
  assert.equal(result.incoming.length, 0);
  assert.equal(result.outgoing.length, 1);
});

test("a delete is not resurrected by a stale copy", function () {
  var deleted = [note("n1", 300, "a", { deleted: true })];
  var stale = [note("n1", 100, "b", { text: "still here" })];

  var result = merge.merge(stale, deleted);
  assert.equal(result.merged[0].deleted, true);
  assert.equal(merge.live(result.merged).length, 0);
});

test("an edit after a delete wins", function () {
  var result = merge.merge(
    [note("n1", 100, "a", { deleted: true })],
    [note("n1", 200, "b", { text: "back" })]
  );
  assert.equal(result.merged[0].text, "back");
  assert.equal(merge.live(result.merged).length, 1);
});

test("merging is commutative", function () {
  var a = [note("n1", 100, "a", { t: "a1" }), note("n2", 400, "a"), note("n4", 500, "a", { deleted: true })];
  var b = [note("n1", 200, "b", { t: "b1" }), note("n3", 300, "b"), note("n4", 100, "b")];

  function sorted(records) {
    return records
      .slice()
      .sort(function (x, y) { return x.id < y.id ? -1 : 1; })
      .map(function (r) { return r.id + ":" + r.updated_at + ":" + !!r.deleted; });
  }

  assert.deepEqual(sorted(merge.merge(a, b).merged), sorted(merge.merge(b, a).merged));
});

test("merging is idempotent", function () {
  var a = [note("n1", 100, "a"), note("n2", 400, "a")];
  var b = [note("n1", 200, "b"), note("n3", 300, "b")];

  var once = merge.merge(a, b).merged;
  var twice = merge.merge(once, b);

  assert.equal(twice.incoming.length, 0, "re-syncing must change nothing");
  assert.equal(twice.merged.length, once.length);
});

test("the same millisecond is broken by device id, not by argument order", function () {
  var a = [note("n1", 100, "a", { t: "a" })];
  var b = [note("n1", 100, "b", { t: "b" })];

  assert.equal(merge.merge(a, b).merged[0].t, "b");
  assert.equal(merge.merge(b, a).merged[0].t, "b");
});

test("fixed-width stamps make string comparison causal", function () {
  // The property the whole design rests on: without padding, "9" > "10".
  assert.ok(stamp(9, "a") < stamp(10, "a"));
  assert.ok(stamp(999, "a") < stamp(1000, "a"));
  assert.ok(stamp(1786000000000, "a") < stamp(1786000000001, "a"));
  assert.equal(merge.compareStamps(stamp(5, "a"), stamp(5, "a")), 0);
  // A malformed stamp must sort oldest rather than throw.
  assert.equal(merge.compareStamps(undefined, stamp(1, "a")), -1);
});
