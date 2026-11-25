// Exact decimal handling for ETB (2 minor units). ArifPay sends and accepts amounts as decimal birr.

const AMOUNT = /^(?:0|[1-9]\d{0,11})(?:\.\d{1,2})?$/;

/** Parses a decimal birr amount (10, 10.5, "10.50") into minor units, or null when it is not exact to 2 places. */
export function toMinor(value: unknown): number | null {
  const text = typeof value === "number" ? String(value) : value;
  if (typeof text !== "string" || !AMOUNT.test(text)) return null;
  const [major, minor = ""] = text.split(".");
  return Number(major) * 100 + Number(minor.padEnd(2, "0"));
}

/** Minor units back to a JSON number in birr (ArifPay returns numbers, e.g. totalAmount: 1800.5). */
export const fromMinor = (minor: number): number => minor / 100;
