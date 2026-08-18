/// <reference path="../pb_data/types.d.ts" />

/**
 * Makes `icon` big enough to hold an actual logo.
 *
 * The field was originally sized for a short icon *name* — max 40 characters,
 * on the assumption the client would ship a bundled icon set and store a key
 * into it. It now stores the image itself, base64-encoded, so that a logo
 * fetched once on a phone reaches every other device by ordinary sync and keeps
 * working when the vendor moves or deletes the file it came from.
 *
 * 40 characters would have silently failed validation on the first subscription
 * that had a logo — and only when syncing to the server, which is exactly the
 * path least likely to be exercised while developing offline.
 *
 * The ceiling here is deliberately larger than the client's own cap
 * (Logo.MAX_STORED_BYTES, 24 KB raw ≈ 32 KB base64). The client is what enforces
 * the size; this only has to be roomy enough not to be the thing that rejects a
 * record the client already considered acceptable.
 */

migrate(
  function (app) {
    var subscriptions = app.findCollectionByNameOrId("subscriptions");

    var icon = subscriptions.fields.getByName("icon");
    if (icon) {
      subscriptions.fields.removeById(icon.id);
    }
    subscriptions.fields.add(
      new TextField({
        name: "icon",
        max: 40000,
      })
    );

    app.save(subscriptions);
  },

  function (app) {
    var subscriptions = app.findCollectionByNameOrId("subscriptions");

    var icon = subscriptions.fields.getByName("icon");
    if (icon) {
      subscriptions.fields.removeById(icon.id);
    }
    // Back to a name-sized field. Any stored logo is far too long to survive
    // this, which is the honest consequence of rolling the feature back.
    subscriptions.fields.add(
      new TextField({
        name: "icon",
        max: 40,
      })
    );

    app.save(subscriptions);
  }
);
