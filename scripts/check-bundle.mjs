// Bundle budget check (packages/ui performanceBudget.mobileBundleKb): exports the current app's Android bundle and fails when the
// gzip-compressed Hermes bytecode exceeds the budget. Raw bytecode and bundled assets are reported for visibility.
// Run from an app folder (it exports that app): npm run bundle:check -w @beavent/public-mobile (also in CI).
import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gzipSync } from "node:zlib";
import { performanceBudget } from "../packages/ui/src/index.ts";

const out = mkdtempSync(join(tmpdir(), "beavent-bundle-"));
try {
  execFileSync("npx", ["expo", "export", "--platform", "android", "--output-dir", out], {
    stdio: ["ignore", "ignore", "inherit"],
  });
  const files = (dir) =>
    readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
      e.isDirectory() ? files(join(dir, e.name)) : [join(dir, e.name)],
    );
  const all = files(out);
  const bundle = all.find((f) => f.endsWith(".hbc"));
  if (!bundle) throw new Error("no Hermes bundle in the export");
  const raw = statSync(bundle).size;
  const gzip = gzipSync(readFileSync(bundle), { level: 9 }).length;
  const assets = all
    .filter((f) => f.includes(`${join(out, "assets")}`))
    .reduce((n, f) => n + statSync(f).size, 0);
  const kb = (n) => Math.round(n / 1024);
  console.log(
    `Hermes bundle: ${kb(gzip)} KB gzip (budget ${performanceBudget.mobileBundleKb} KB), ${kb(raw)} KB raw`,
  );
  console.log(
    `Bundled assets: ${kb(assets)} KB (fonts, images; part of the download size, not the JS budget)`,
  );
  if (kb(gzip) > performanceBudget.mobileBundleKb) {
    console.error("Mobile bundle is over budget (docs/design/beavent/MOBILE_UI_GUIDELINES.md §5).");
    process.exit(1);
  }
} finally {
  rmSync(out, { recursive: true, force: true });
}
