/// <reference path="../pb_data/types.d.ts" />

/**
 * Turns the server into a sync peer rather than the owner of the data.
 *
 * Devices now hold the truth and compute their own schedules, so PocketBase's
 * job changes: it is the peer that happens to always be on. To merge on the
 * same rules as every device it needs the same envelope on every record —
 * a hybrid logical clock and a tombstone flag — and it must accept ids minted
 * on a device rather than generating its own.
 *
 * Charges are *derived* and are no longer stored: each device recomputes them.
 * The generated `charges` collection is therefore dropped, and the user's
 * deliberate adjustments move to `charge_overrides`, keyed by
 * (subscription, due_date) so two devices adjusting the same charge produce
 * one record instead of two rivals.
 */

var AUTHED = "@request.auth.id != ''";
var DATE_PATTERN = "^\\d{4}-\\d{2}-\\d{2}$";
// 16-digit millis, 5-digit counter, 16-char device id — fixed width, because
// lexicographic order is what makes this comparable.
var HLC_PATTERN = "^\\d{16}-\\d{5}-[0-9a-z]{16}$";

migrate(
  function (app) {
    // ---------------------------------------------------- sync envelope
    var synced = ["subscriptions", "price_periods"];
    for (var i = 0; i < synced.length; i++) {
      var collection = app.findCollectionByNameOrId(synced[i]);

      collection.fields.add(
        new TextField({
          name: "updated_at",
          required: true,
          pattern: HLC_PATTERN,
        })
      );
      collection.fields.add(
        new BoolField({
          name: "deleted",
        })
      );

      app.save(collection);
    }

    // ---------------------------------------------------- charge overrides
    var subscriptions = app.findCollectionByNameOrId("subscriptions");

    var overrides = new Collection({
      type: "base",
      name: "charge_overrides",
      listRule: AUTHED,
      viewRule: AUTHED,
      createRule: AUTHED,
      updateRule: AUTHED,
      deleteRule: AUTHED,
      fields: [
        {
          name: "subscription",
          type: "relation",
          required: true,
          maxSelect: 1,
          cascadeDelete: true,
          collectionId: subscriptions.id,
        },
        { name: "due_date", type: "text", required: true, pattern: DATE_PATTERN },
        {
          name: "kind",
          type: "select",
          required: true,
          maxSelect: 1,
          values: ["skipped", "refunded", "amount"],
        },
        { name: "amount_minor", type: "number", onlyInt: true },
        { name: "updated_at", type: "text", required: true, pattern: HLC_PATTERN },
        { name: "deleted", type: "bool" },
      ],
      indexes: [
        // One override per charge. The client derives the record id from the
        // same pair, so this can never be violated by a legitimate sync.
        "CREATE UNIQUE INDEX idx_override_unique ON charge_overrides (subscription, due_date)",
      ],
    });
    app.save(overrides);

    // ---------------------------------------------------- drop derived data
    // Charges are recomputed on every device from the subscription and its
    // price history. Storing them here would mean two sources of truth for
    // something that is a pure function of the other two.
    try {
      app.delete(app.findCollectionByNameOrId("v_upcoming"));
    } catch (err) {
      // view may already be gone
    }
    try {
      app.delete(app.findCollectionByNameOrId("v_monthly_totals"));
    } catch (err) {
      // view may already be gone
    }
    try {
      app.delete(app.findCollectionByNameOrId("v_category_totals"));
    } catch (err) {
      // view may already be gone
    }
    try {
      app.delete(app.findCollectionByNameOrId("charges"));
    } catch (err) {
      // already gone
    }
  },

  function (app) {
    try {
      app.delete(app.findCollectionByNameOrId("charge_overrides"));
    } catch (err) {
      // already gone
    }

    var synced = ["subscriptions", "price_periods"];
    for (var i = 0; i < synced.length; i++) {
      var collection = app.findCollectionByNameOrId(synced[i]);
      var updatedAt = collection.fields.getByName("updated_at");
      if (updatedAt) collection.fields.removeById(updatedAt.id);
      var deleted = collection.fields.getByName("deleted");
      if (deleted) collection.fields.removeById(deleted.id);
      app.save(collection);
    }
  }
);
