/// <reference path="../pb_data/types.d.ts" />

/**
 * SubTracker initial schema.
 *
 * Four base collections plus four read-only view collections. The views exist
 * because PocketBase's record API has no GROUP BY or SUM — every aggregate the
 * dashboard needs is defined here as SQL instead of being assembled client-side.
 *
 * Conventions enforced throughout:
 *   - money is an integer number of minor units (pence), never a decimal
 *   - calendar dates are text "YYYY-MM-DD", never PocketBase date fields,
 *     so they cannot shift by a day under a timezone change
 */

var DATE_PATTERN = "^\\d{4}-\\d{2}-\\d{2}$";

// Single user behind Tailscale, so every rule is simply "must be logged in".
var AUTHED = "@request.auth.id != ''";

/** Effective amount of a charge, shared by every aggregate view. */
var AMOUNT_EXPR =
  "CASE WHEN c.status = 'skipped' THEN 0 " +
  "WHEN c.has_override = 1 THEN c.actual_amount_minor " +
  "ELSE c.amount_minor END";

/**
 * PocketBase parses a view's SELECT list to derive its fields, and that parser
 * only accepts plain identifiers — a concatenation or CAST in the outer query
 * fails with "invalid identifier parts". So every view is written as a
 * subquery doing the real work, wrapped in an outer projection of bare columns.
 */
function viewOf(columns, inner) {
  var projected = columns
    .map(function (c) {
      return "t." + c + " AS " + c;
    })
    .join(", ");
  return "SELECT " + projected + " FROM (" + inner + ") t";
}

migrate(
  function (app) {
    // ------------------------------------------------------------ subscriptions
    var subscriptions = new Collection({
      type: "base",
      name: "subscriptions",
      listRule: AUTHED,
      viewRule: AUTHED,
      createRule: AUTHED,
      updateRule: AUTHED,
      deleteRule: AUTHED,
      fields: [
        { name: "name", type: "text", required: true, max: 120 },
        // Free text for now; becomes the matching key for email import later.
        { name: "vendor", type: "text", max: 120 },
        {
          name: "category",
          type: "select",
          required: true,
          maxSelect: 1,
          values: ["entertainment", "software", "utilities", "health", "finance", "other"],
        },
        { name: "currency", type: "text", required: true, min: 3, max: 3, pattern: "^[A-Z]{3}$" },
        {
          name: "cycle_unit",
          type: "select",
          required: true,
          maxSelect: 1,
          values: ["day", "week", "month", "year"],
        },
        // cycle_unit + cycle_count expresses every real cycle: month/3 is quarterly.
        { name: "cycle_count", type: "number", required: true, onlyInt: true, min: 1 },
        { name: "anchor_date", type: "text", required: true, pattern: DATE_PATTERN },
        {
          name: "status",
          type: "select",
          required: true,
          maxSelect: 1,
          values: ["active", "trial", "paused", "cancelled"],
        },
        { name: "end_date", type: "text", pattern: DATE_PATTERN },
        { name: "trial_end", type: "text", pattern: DATE_PATTERN },
        { name: "payment_method", type: "text", max: 60 },
        { name: "colour", type: "text", max: 9 },
        { name: "icon", type: "text", max: 40 },
        { name: "notes", type: "text", max: 2000 },
        { name: "created", type: "autodate", onCreate: true },
        { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
      ],
      indexes: [
        "CREATE INDEX idx_subscriptions_status ON subscriptions (status)",
        "CREATE INDEX idx_subscriptions_vendor ON subscriptions (vendor)",
      ],
    });
    app.save(subscriptions);

    // ------------------------------------------------------------ price_periods
    var pricePeriods = new Collection({
      type: "base",
      name: "price_periods",
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
        { name: "amount_minor", type: "number", required: true, onlyInt: true, min: 0 },
        { name: "effective_from", type: "text", required: true, pattern: DATE_PATTERN },
        { name: "note", type: "text", max: 200 },
        { name: "created", type: "autodate", onCreate: true },
        { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
      ],
      indexes: [
        "CREATE UNIQUE INDEX idx_price_periods_unique ON price_periods (subscription, effective_from)",
      ],
    });
    app.save(pricePeriods);

    // ------------------------------------------------------------ charges
    var charges = new Collection({
      type: "base",
      name: "charges",
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
        { name: "amount_minor", type: "number", required: true, onlyInt: true },
        { name: "currency", type: "text", required: true, min: 3, max: 3, pattern: "^[A-Z]{3}$" },
        // No "posted" here on purpose: paid-vs-upcoming is derived from
        // due_date < today, so history stays correctable and nothing has to
        // mutate rows on a timer.
        {
          name: "status",
          type: "select",
          required: true,
          maxSelect: 1,
          values: ["projected", "skipped", "refunded"],
        },
        {
          name: "source",
          type: "select",
          required: true,
          maxSelect: 1,
          values: ["generated", "manual", "email"],
        },
        // Explicit flag because an empty PocketBase number field reads as 0,
        // which is indistinguishable from a genuine £0.00 charge.
        { name: "has_override", type: "bool" },
        { name: "actual_amount_minor", type: "number", onlyInt: true },
        { name: "external_ref", type: "text", max: 200 },
        { name: "created", type: "autodate", onCreate: true },
        { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
      ],
      indexes: [
        // Makes the projection an upsert rather than a duplicate factory.
        "CREATE UNIQUE INDEX idx_charges_unique ON charges (subscription, due_date)",
        "CREATE INDEX idx_charges_due_date ON charges (due_date)",
      ],
    });
    app.save(charges);

    // ------------------------------------------------------------ settings
    var settings = new Collection({
      type: "base",
      name: "settings",
      listRule: AUTHED,
      viewRule: AUTHED,
      createRule: null,
      updateRule: AUTHED,
      deleteRule: null,
      fields: [
        { name: "home_currency", type: "text", required: true, min: 3, max: 3, pattern: "^[A-Z]{3}$" },
        { name: "months_back", type: "number", required: true, onlyInt: true, min: 1, max: 120 },
        { name: "months_forward", type: "number", required: true, onlyInt: true, min: 1, max: 120 },
        { name: "reminder_days", type: "number", required: true, onlyInt: true, min: 0, max: 60 },
        { name: "ntfy_url", type: "text", max: 200 },
        { name: "ntfy_topic", type: "text", max: 100 },
        { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
      ],
    });
    app.save(settings);

    var defaults = new Record(settings);
    defaults.set("home_currency", "GBP");
    defaults.set("months_back", 36);
    defaults.set("months_forward", 24);
    defaults.set("reminder_days", 3);
    defaults.set("ntfy_url", "");
    defaults.set("ntfy_topic", "");
    app.save(defaults);

    // ------------------------------------------------------------ views
    // Cash flow per month: what actually leaves the account, when.
    app.save(
      new Collection({
        type: "view",
        name: "v_monthly_totals",
        listRule: AUTHED,
        viewRule: AUTHED,
        viewQuery: viewOf(
          ["id", "month", "total_minor", "charge_count", "settled_count"],
          "SELECT strftime('%Y-%m', c.due_date) AS id, " +
            "strftime('%Y-%m', c.due_date) AS month, " +
            "SUM(" + AMOUNT_EXPR + ") AS total_minor, " +
            "COUNT(*) AS charge_count, " +
            "SUM(CASE WHEN c.due_date < date('now') THEN 1 ELSE 0 END) AS settled_count " +
            "FROM charges c GROUP BY 1"
        ),
      })
    );

    app.save(
      new Collection({
        type: "view",
        name: "v_category_totals",
        listRule: AUTHED,
        viewRule: AUTHED,
        viewQuery: viewOf(
          ["id", "category", "month", "total_minor", "charge_count"],
          "SELECT (s.category || '|' || strftime('%Y-%m', c.due_date)) AS id, " +
            "s.category AS category, " +
            "strftime('%Y-%m', c.due_date) AS month, " +
            "SUM(" + AMOUNT_EXPR + ") AS total_minor, " +
            "COUNT(*) AS charge_count " +
            "FROM charges c JOIN subscriptions s ON s.id = c.subscription " +
            "GROUP BY 1, 2, 3"
        ),
      })
    );

    app.save(
      new Collection({
        type: "view",
        name: "v_upcoming",
        listRule: AUTHED,
        viewRule: AUTHED,
        viewQuery: viewOf(
          [
            "id", "subscription", "name", "colour", "category",
            "payment_method", "due_date", "currency", "amount_minor", "days_until",
          ],
          "SELECT c.id AS id, c.subscription AS subscription, s.name AS name, " +
            "s.colour AS colour, s.category AS category, s.payment_method AS payment_method, " +
            "c.due_date AS due_date, c.currency AS currency, " +
            "(" + AMOUNT_EXPR + ") AS amount_minor, " +
            "CAST(julianday(c.due_date) - julianday(date('now')) AS INTEGER) AS days_until " +
            "FROM charges c JOIN subscriptions s ON s.id = c.subscription " +
            "WHERE c.due_date >= date('now') AND c.status = 'projected'"
        ),
      })
    );

    // Run rate: normalised monthly cost of everything currently live. Mirrors
    // monthlyRunRateMinor() in engine/money.js — the two are cross-checked by test.
    app.save(
      new Collection({
        type: "view",
        name: "v_run_rate",
        listRule: AUTHED,
        viewRule: AUTHED,
        viewQuery: viewOf(
          [
            "id", "name", "category", "currency",
            "cycle_unit", "cycle_count", "amount_minor", "monthly_minor",
          ],
          "SELECT s.id AS id, s.name AS name, s.category AS category, " +
            "s.currency AS currency, s.cycle_unit AS cycle_unit, s.cycle_count AS cycle_count, " +
            "p.amount_minor AS amount_minor, " +
            "CAST(ROUND(p.amount_minor * (CASE s.cycle_unit " +
            "WHEN 'day' THEN 365.25 / s.cycle_count " +
            "WHEN 'week' THEN 365.25 / (7.0 * s.cycle_count) " +
            "WHEN 'month' THEN 12.0 / s.cycle_count " +
            "WHEN 'year' THEN 1.0 / s.cycle_count END) / 12.0) AS INTEGER) AS monthly_minor " +
            "FROM subscriptions s JOIN price_periods p ON p.id = (" +
            "SELECT p2.id FROM price_periods p2 WHERE p2.subscription = s.id " +
            "AND p2.effective_from <= date('now') ORDER BY p2.effective_from DESC LIMIT 1) " +
            "WHERE s.status IN ('active', 'trial') " +
            "AND (s.end_date IS NULL OR s.end_date = '' OR s.end_date >= date('now'))"
        ),
      })
    );
  },

  function (app) {
    var names = [
      "v_run_rate",
      "v_upcoming",
      "v_category_totals",
      "v_monthly_totals",
      "settings",
      "charges",
      "price_periods",
      "subscriptions",
    ];
    for (var i = 0; i < names.length; i++) {
      try {
        app.delete(app.findCollectionByNameOrId(names[i]));
      } catch (err) {
        // already gone
      }
    }
  }
);
