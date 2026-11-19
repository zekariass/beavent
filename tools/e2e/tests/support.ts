import { readFileSync } from "node:fs";

const API_LOG = process.env.API_LOG ?? "";

function codes(destination: string): string[] {
  const pattern = new RegExp(`to ${destination.replace(/[+.]/g, "\\$&")}\\] .*?(\\d{6})`, "g");
  return [...readFileSync(API_LOG, "utf8").matchAll(pattern)].map((m) => m[1]!);
}

/** How many codes the `log` SMS/email provider has written for this destination so far. */
export const codeCount = (destination: string): number => codes(destination).length;

/**
 * The newest one-time code for this phone number or address, waiting until there are more than `seen` codes (pass
 * `codeCount(...)` taken before requesting, so an older code is never returned).
 */
export async function codeFor(destination: string, seen = 0): Promise<string> {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const all = codes(destination);
    if (all.length > seen) return all.at(-1)!;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`no new code for ${destination} in ${API_LOG}`);
}

export const newPhone = () => `+2519${String(Math.floor(Math.random() * 1e8)).padStart(8, "0")}`;
