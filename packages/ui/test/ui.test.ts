import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import {
  color,
  contrastPairs,
  contrastRatio,
  cssVariables,
  fontSizePx,
  lineHeightPx,
  scriptOfText,
  textStyle,
  tokenStylesheet,
} from "../src/index.ts";

test("both schemes define the same tokens", () => {
  assert.deepEqual(Object.keys(color.light).sort(), Object.keys(color.dark).sort());
});

for (const scheme of ["light", "dark"] as const) {
  test(`allowed colour pairs meet WCAG contrast (${scheme})`, () => {
    for (const { fg, bg, min } of contrastPairs) {
      const ratio = contrastRatio(color[scheme][fg], color[scheme][bg]);
      assert.ok(
        ratio >= min,
        `${fg} on ${bg} is ${ratio.toFixed(2)}:1, needs ${min}:1 (${scheme})`,
      );
    }
  });
}

test("contrast ratio matches WCAG reference values", () => {
  assert.equal(contrastRatio("#000000", "#ffffff").toFixed(1), "21.0");
  assert.equal(contrastRatio("#ffffff", "#ffffff").toFixed(1), "1.0");
});

test("Ethiopic text gets more line height and a minimum size", () => {
  assert.equal(lineHeightPx("md", "Latn"), 23);
  assert.equal(lineHeightPx("md", "Ethi"), 27);
  assert.equal(lineHeightPx("xs", "Ethi"), 24); // 12 px is raised to the 14 px minimum
  assert.equal(fontSizePx("xs", "Latn"), 12);
  assert.equal(fontSizePx("xs", "Ethi"), 14);
  assert.deepEqual(textStyle("caption", "Ethi"), {
    fontSize: 14,
    lineHeight: 24,
    fontWeight: "400",
  });
  assert.deepEqual(textStyle("display", "Latn"), {
    fontSize: 28,
    lineHeight: 34,
    fontWeight: "600",
  });
});

test("script detection for mixed content", () => {
  assert.equal(scriptOfText("Abeba Beauty"), "Latn");
  assert.equal(scriptOfText("አበባ ውበት"), "Ethi");
  assert.equal(scriptOfText("Salon ቦሌ"), "Ethi");
});

test("CSS variables", () => {
  const css = cssVariables("dark");
  assert.match(css, /--bv-color-on-primary: #06261d;/);
  assert.match(css, /--bv-color-primary: #5cc8a1;/);
  assert.match(css, /--bv-space-4: 16px;/);
});

test("tokens.css is generated from the current tokens", () => {
  const committed = readFileSync(new URL("../tokens.css", import.meta.url), "utf8");
  assert.equal(committed, tokenStylesheet(), "run `npm run generate -w @beavent/ui`");
});
