"use strict";

/**
 * Last-write-wins merge, mirroring Merge.kt on the devices.
 *
 * The server is now a peer rather than the owner of the data, so it has to
 * merge on exactly the same rules as every device — otherwise syncing with the
 * server would produce a different result from syncing with a phone, and the
 * whole thing stops converging.
 *
 * `updated_at` is a fixed-width encoded hybrid logical clock, so ordinary string
 * comparison is causal ordering. That is the entire reason for the fixed width.
 */

/** Records with a malformed stamp sort oldest, rather than throwing mid-merge. */
function compareStamps(a, b) {
  var left = typeof a === "string" ? a : "";
  var right = typeof b === "string" ? b : "";
  if (left < right) return -1;
  if (left > right) return 1;
  return 0;
}

/**
 * @returns {{merged: Array, incoming: Array, outgoing: Array}}
 *   incoming — remote records that won, and so need writing locally
 *   outgoing — local records the peer is missing or holds a stale copy of
 */
function merge(local, remote) {
  var byId = {};
  var order = [];

  for (var i = 0; i < local.length; i++) {
    var mine = local[i];
    if (!(mine.id in byId)) order.push(mine.id);
    byId[mine.id] = mine;
  }

  var incoming = [];
  for (var j = 0; j < remote.length; j++) {
    var theirs = remote[j];
    var existing = byId[theirs.id];
    if (!existing || compareStamps(theirs.updated_at, existing.updated_at) > 0) {
      if (!existing) order.push(theirs.id);
      byId[theirs.id] = theirs;
      incoming.push(theirs);
    }
  }

  var remoteById = {};
  for (var k = 0; k < remote.length; k++) remoteById[remote[k].id] = remote[k];

  var outgoing = [];
  for (var m = 0; m < local.length; m++) {
    var candidate = local[m];
    var counterpart = remoteById[candidate.id];
    if (!counterpart || compareStamps(candidate.updated_at, counterpart.updated_at) > 0) {
      outgoing.push(candidate);
    }
  }

  var merged = [];
  for (var n = 0; n < order.length; n++) merged.push(byId[order[n]]);

  return { merged: merged, incoming: incoming, outgoing: outgoing };
}

/** Everything still alive, for anything that needs to act on the data. */
function live(records) {
  return records.filter(function (record) {
    return !record.deleted;
  });
}

module.exports = {
  compareStamps: compareStamps,
  merge: merge,
  live: live,
};
