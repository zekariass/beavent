import { readFileSync } from "node:fs";

const API_LOG = process.env.API_LOG ?? "";

/** The latest one-time code the `log` SMS/email provider wrote for this phone number or address. */
export async function codeFor(destination: string): Promise<string> {
  const pattern = new RegExp(`to ${destination.replace(/[+.]/g, "\\$&")}\\] .*?(\\d{6})`, "g");
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const matches = [...readFileSync(API_LOG, "utf8").matchAll(pattern)];
    const last = matches.at(-1);
    if (last) return last[1]!;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`no code for ${destination} in ${API_LOG}`);
}

export const newPhone = () => `+2519${String(Math.floor(Math.random() * 1e8)).padStart(8, "0")}`;
