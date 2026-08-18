"use strict";

/**
 * Copies the tested engine into pb_hooks/lib/ so PocketBase can load it.
 *
 * backend/engine/ is the source of truth and the thing `npm test` exercises.
 * pb_hooks/lib/ is generated: the only edit is rewriting relative requires,
 * because PocketBase resolves hook modules against the __hooks directory
 * rather than the requiring file.
 */

var fs = require("node:fs");
var path = require("node:path");

var root = path.join(__dirname, "..");
var src = path.join(root, "engine");
var dest = path.join(root, "pb_hooks", "lib");

fs.mkdirSync(dest, { recursive: true });

var copied = [];
var files = fs.readdirSync(src);

for (var i = 0; i < files.length; i++) {
  var file = files[i];
  if (!/\.js$/.test(file)) continue;

  var code = fs.readFileSync(path.join(src, file), "utf8");
  var rewritten = code.replace(/require\("\.\/([\w.-]+)"\)/g, 'require(__hooks + "/lib/$1")');

  var banner =
    "// GENERATED FILE — do not edit.\n" +
    "// Source: backend/engine/" + file + "\n" +
    "// Regenerate with: npm run sync-hooks\n\n";

  fs.writeFileSync(path.join(dest, file), banner + rewritten);
  copied.push(file);
}

console.log("synced " + copied.length + " engine file(s) to pb_hooks/lib: " + copied.join(", "));
