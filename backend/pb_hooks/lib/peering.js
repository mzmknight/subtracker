"use strict";

/**
 * The server acting as a sync peer.
 *
 * Same exchange as between two devices: the caller sends everything it has,
 * this merges it, and replies with only what the caller is missing. The merge
 * rules are shared with the devices via engine/merge.js, so syncing with the
 * server and syncing with a phone produce the same result.
 *
 * Note what is *not* here: no projection. Charges are derived and every device
 * computes its own, so the server stores only intent.
 */

var merge = require(__hooks + "/lib/merge.js");

/** Fields carried by each synced collection, in PocketBase field names. */
var FIELDS = {
  subscriptions: [
    "name", "vendor", "category", "currency", "cycle_unit", "cycle_count",
    "anchor_date", "status", "end_date", "trial_end", "payment_method",
    "colour", "icon", "notes",
  ],
  price_periods: ["subscription", "amount_minor", "effective_from", "note"],
  charge_overrides: ["subscription", "due_date", "kind", "amount_minor"],
};

var INTEGER_FIELDS = ["cycle_count", "amount_minor"];

function toPlain(record, collection) {
  var out = {
    id: record.id,
    updated_at: record.getString("updated_at"),
    deleted: record.getBool("deleted"),
  };
  var fields = FIELDS[collection];
  for (var i = 0; i < fields.length; i++) {
    var field = fields[i];
    if (INTEGER_FIELDS.indexOf(field) !== -1) {
      out[field] = record.getInt(field);
    } else {
      out[field] = record.getString(field);
    }
  }
  return out;
}

function readAll(app, collection) {
  var records = app.findAllRecords(collection);
  var out = [];
  for (var i = 0; i < records.length; i++) out.push(toPlain(records[i], collection));
  return out;
}

/**
 * Upsert by id. Ids are minted on the device, so an incoming record either
 * matches an existing row or creates one with the id the device chose — the
 * record must keep the same identity everywhere or the merge falls apart.
 */
function writeRecord(app, collectionName, plain) {
  var collection = app.findCollectionByNameOrId(collectionName);
  var record;
  try {
    record = app.findRecordById(collectionName, plain.id);
  } catch (err) {
    record = new Record(collection);
    record.set("id", plain.id);
  }

  record.set("updated_at", plain.updated_at);
  record.set("deleted", !!plain.deleted);

  var fields = FIELDS[collectionName];
  for (var i = 0; i < fields.length; i++) {
    var field = fields[i];
    if (plain[field] !== undefined && plain[field] !== null) {
      record.set(field, plain[field]);
    }
  }

  app.save(record);
}

/**
 * Merge one collection and return what the caller still needs.
 * Everything is applied inside the caller's transaction.
 */
function mergeCollection(app, collectionName, incoming) {
  var local = readAll(app, collectionName);
  var result = merge.merge(local, incoming || []);

  for (var i = 0; i < result.incoming.length; i++) {
    writeRecord(app, collectionName, result.incoming[i]);
  }

  return { applied: result.incoming.length, outgoing: result.outgoing };
}

/**
 * Full exchange. Wrapped in a transaction so a failure part-way cannot leave
 * half a device's history behind.
 */
function exchange(app, payload) {
  var reply = { subscriptions: [], prices: [], overrides: [] };
  var applied = 0;

  app.runInTransaction(function (txApp) {
    var subscriptions = mergeCollection(txApp, "subscriptions", payload.subscriptions);
    var prices = mergeCollection(txApp, "price_periods", payload.prices);
    var overrides = mergeCollection(txApp, "charge_overrides", payload.overrides);

    reply.subscriptions = subscriptions.outgoing;
    reply.prices = prices.outgoing;
    reply.overrides = overrides.outgoing;
    applied = subscriptions.applied + prices.applied + overrides.applied;
  });

  return { applied: applied, payload: reply };
}

module.exports = {
  FIELDS: FIELDS,
  toPlain: toPlain,
  readAll: readAll,
  writeRecord: writeRecord,
  exchange: exchange,
};
