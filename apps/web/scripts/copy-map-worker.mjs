// MapLibre runs its map work in a web worker that the bundler cannot find on its own. Serve the worker (and the code
// it shares with the map) from /maplibre/ and tell MapLibre where it is (components/discovery/ResultsMap.tsx).
// Runs before `dev` and `build`; the copies are not committed.
import { copyFileSync, mkdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";

const dist = dirname(createRequire(import.meta.url).resolve("maplibre-gl/package.json"));
const target = join(import.meta.dirname, "..", "public", "maplibre");
mkdirSync(target, { recursive: true });
for (const file of ["maplibre-gl-worker.mjs", "maplibre-gl-shared.mjs"]) {
  copyFileSync(join(dist, "dist", file), join(target, file));
}
