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

/**
 * A phone number whose last four digits are `ending`. The payment stubs decide what a payer does by them (ArifPay stub:
 * 0001 pays, 0002 is declined, 0003 never pays, 0005 pays without a notification).
 */
export const phoneEnding = (ending: string) =>
  `+2519${String(Math.floor(Math.random() * 1e4)).padStart(4, "0")}${ending}`;

/**
 * The gift card code the `log` SMS provider wrote for this phone number (plan 3.8), waiting for it to arrive. The API
 * must run with `BEAVENT_RETENTION_WORKER_INTERVAL_MS=2000` so cards are delivered within seconds.
 */
export async function giftCardCodeFor(destination: string): Promise<string> {
  const pattern = new RegExp(
    `to ${destination.replace(/[+.]/g, "\\$&")}\\] .*?([A-Z2-9]{4}(?:-[A-Z2-9]{4}){3})`,
    "g",
  );
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const found = [...readFileSync(API_LOG, "utf8").matchAll(pattern)];
    if (found.length > 0) return found.at(-1)![1]!;
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error(`no gift card code for ${destination} in ${API_LOG}`);
}

/** The link of the newest receipt text the `log` SMS provider wrote for this phone number (plan 4.3). */
export async function receiptLinkFor(destination: string): Promise<string> {
  const pattern = new RegExp(
    `to ${destination.replace(/[+.]/g, "\\$&")}\\] .*?(https?://\\S*/receipt/[A-Za-z0-9_-]{43})`,
    "g",
  );
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const found = [...readFileSync(API_LOG, "utf8").matchAll(pattern)];
    if (found.length > 0) return found.at(-1)![1]!;
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error(`no receipt link for ${destination} in ${API_LOG}`);
}
