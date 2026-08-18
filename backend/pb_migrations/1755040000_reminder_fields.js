/// <reference path="../pb_data/types.d.ts" />

/**
 * Per-subscription reminder rules, so they survive a round trip through the
 * server.
 *
 * Devices compute and post their own reminders now — the server plays no part in
 * delivering them. But the *rule* is user intent stored on the subscription, and
 * a field the server does not know about is a field the server drops: sync to
 * PocketBase and back, and every "never remind me about this one" would quietly
 * become "remind me like everything else".
 *
 * This is the second time that has happened (see 1754960000_logo_field.js, where
 * `icon` was sized for a name and given an image). Anything added to
 * SubscriptionRecord needs a migration here in the same breath.
 *
 * notify_mode  ''       follow the device's own global setting
 *              'off'    never
 *              'custom' use the two numbers below
 */

migrate(
  function (app) {
    var subscriptions = app.findCollectionByNameOrId("subscriptions");

    subscriptions.fields.add(
      new SelectField({
        name: "notify_mode",
        maxSelect: 1,
        values: ["off", "custom"],
      })
    );
    subscriptions.fields.add(
      new NumberField({
        name: "notify_days_before",
        onlyInt: true,
        min: 0,
        max: 30,
      })
    );
    // Minutes since local midnight. Stored as a number rather than a time so it
    // means the same thing on a device in a different timezone: the rule is
    // "09:00 wherever you are", not an instant.
    subscriptions.fields.add(
      new NumberField({
        name: "notify_minute",
        onlyInt: true,
        min: 0,
        max: 1439,
      })
    );

    app.save(subscriptions);
  },

  function (app) {
    var subscriptions = app.findCollectionByNameOrId("subscriptions");
    var names = ["notify_mode", "notify_days_before", "notify_minute"];

    for (var i = 0; i < names.length; i++) {
      var field = subscriptions.fields.getByName(names[i]);
      if (field) subscriptions.fields.removeById(field.id);
    }

    app.save(subscriptions);
  }
);
