import assert from "node:assert/strict";
import { test } from "node:test";
import { color, cssVariables, lineHeightPx } from "../src/index.ts";

test("both schemes define the same tokens", () => {
  assert.deepEqual(Object.keys(color.light).sort(), Object.keys(color.dark).sort());
});

test("Ethiopic text gets more line height and a minimum size", () => {
  assert.equal(lineHeightPx("md", "Latn"), 23);
  assert.equal(lineHeightPx("md", "Ethi"), 27);
  assert.equal(lineHeightPx("xs", "Ethi"), 24); // 12 px is raised to the 14 px minimum
});

test("CSS variables", () => {
  const css = cssVariables("dark");
  assert.match(css, /--bv-color-on-primary: #14102b;/);
  assert.match(css, /--bv-space-4: 16px;/);
});
