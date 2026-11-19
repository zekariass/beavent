// Exact decimal handling for ETB/USD (2 minor units). No floating point for money.

const AMOUNT = /^(?:0|[1-9]\d{0,11})(?:\.\d{1,2})?$/;

/** Parses a Chapa-style decimal amount ("1500", "1500.5", "1500.50") into minor units. */
export function toMinor(value: unknown): number | null {
  const text = typeof value === "number" ? String(value) : value;
  if (typeof text !== "string" || !AMOUNT.test(text)) return null;
  const [major, minor = ""] = text.split(".");
  return Number(major) * 100 + Number(minor.padEnd(2, "0"));
}

export function fromMinor(minor: number): string {
  const sign = minor < 0 ? "-" : "";
  const abs = Math.abs(minor);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}

/** Platform share for a split instruction; percentage rounds half up to the minor unit. */
export function platformShareMinor(
  amountMinor: number,
  split: { split_type: "percentage" | "flat"; split_value: number },
): number {
  if (split.split_type === "flat") {
    const flat = toMinor(String(split.split_value));
    return Math.min(flat ?? 0, amountMinor);
  }
  // split_value is a fraction (0.03 = 3%). Work in basis points to avoid float drift.
  const bps = Math.round(split.split_value * 10000);
  return Math.min(Math.floor((amountMinor * bps + 5000) / 10000), amountMinor);
}
