// Money is always an integer amount of minor units plus an ISO 4217 currency (docs/05 §2). No floating point:
// amounts are parsed and printed as decimal strings, and arithmetic stays in integers.

export interface Money {
  amountMinor: number;
  currency: string;
}

/** Minor units and cash rounding per currency (mirrors ref.currencies; cash rounding in minor units). */
export const CURRENCIES: Readonly<
  Record<string, { minorUnits: number; cashRoundingMinor: number }>
> = {
  ETB: { minorUnits: 2, cashRoundingMinor: 1 },
  KES: { minorUnits: 2, cashRoundingMinor: 100 },
  UGX: { minorUnits: 0, cashRoundingMinor: 50 },
  TZS: { minorUnits: 2, cashRoundingMinor: 5000 },
  RWF: { minorUnits: 0, cashRoundingMinor: 5 },
  BIF: { minorUnits: 0, cashRoundingMinor: 10 },
  DJF: { minorUnits: 0, cashRoundingMinor: 5 },
  SOS: { minorUnits: 2, cashRoundingMinor: 100 },
  SSP: { minorUnits: 2, cashRoundingMinor: 100 },
  NGN: { minorUnits: 2, cashRoundingMinor: 100 },
  GHS: { minorUnits: 2, cashRoundingMinor: 10 },
  ZAR: { minorUnits: 2, cashRoundingMinor: 10 },
  EGP: { minorUnits: 2, cashRoundingMinor: 25 },
  MAD: { minorUnits: 2, cashRoundingMinor: 10 },
  XOF: { minorUnits: 0, cashRoundingMinor: 5 },
  XAF: { minorUnits: 0, cashRoundingMinor: 5 },
  USD: { minorUnits: 2, cashRoundingMinor: 1 },
  EUR: { minorUnits: 2, cashRoundingMinor: 1 },
  GBP: { minorUnits: 2, cashRoundingMinor: 1 },
};

export function minorUnits(currency: string): number {
  const info = CURRENCIES[currency];
  if (!info) throw new RangeError(`unsupported currency ${currency}`);
  return info.minorUnits;
}

function checked(amountMinor: number): number {
  if (!Number.isSafeInteger(amountMinor))
    throw new RangeError(`amount is not a safe integer: ${amountMinor}`);
  return amountMinor;
}

export const money = (amountMinor: number, currency: string): Money => {
  minorUnits(currency);
  return { amountMinor: checked(amountMinor), currency };
};

export const zero = (currency: string): Money => money(0, currency);

/** Parses "1500", "1,500.5", "-0.07" exactly; more decimals than the currency allows is an error. */
export function parse(text: string, currency: string): Money {
  const digits = minorUnits(currency);
  const match = /^\s*(-)?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?\s*$/.exec(text);
  if (!match) throw new RangeError(`not an amount: "${text}"`);
  const [, sign, whole = "", fraction = ""] = match;
  if (fraction.length > digits)
    throw new RangeError(`${currency} allows ${digits} decimal places: "${text}"`);
  const minor = Number(whole.replace(/,/g, "") + fraction.padEnd(digits, "0"));
  return money(sign ? -minor : minor, currency);
}

/** Plain decimal string ("1500.50"), as used by payment providers. */
export function toDecimal({ amountMinor, currency }: Money): string {
  const digits = minorUnits(currency);
  const abs = Math.abs(amountMinor)
    .toString()
    .padStart(digits + 1, "0");
  const text = digits === 0 ? abs : `${abs.slice(0, -digits)}.${abs.slice(-digits)}`;
  return amountMinor < 0 ? `-${text}` : text;
}

/**
 * Localized display, e.g. "ETB 1,500.00" (en) or "ብር 1,500.00" (am). Formats the decimal string, so large amounts
 * are not routed through floating point.
 */
export function format(value: Money, locale: string, options: { cash?: boolean } = {}): string {
  const shown = options.cash ? cashRound(value) : value;
  const digits = minorUnits(value.currency);
  const formatter = new Intl.NumberFormat(locale, {
    style: "currency",
    currency: value.currency,
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
  return formatter.format(toDecimal(shown) as unknown as number);
}

function sameCurrency(a: Money, b: Money): void {
  if (a.currency !== b.currency)
    throw new TypeError(`currency mismatch: ${a.currency} vs ${b.currency}`);
}

export function add(a: Money, ...rest: Money[]): Money {
  return rest.reduce((sum, m) => {
    sameCurrency(sum, m);
    return money(sum.amountMinor + m.amountMinor, sum.currency);
  }, a);
}

export function subtract(a: Money, b: Money): Money {
  sameCurrency(a, b);
  return money(a.amountMinor - b.amountMinor, a.currency);
}

export function multiply(a: Money, quantity: number): Money {
  if (!Number.isInteger(quantity)) throw new RangeError("quantity must be an integer");
  return money(a.amountMinor * quantity, a.currency);
}

/** Integer division rounding half up (away from zero for halves), the fee rounding rule (docs/04). */
function divideHalfUp(numerator: number, denominator: number): number {
  const sign = Math.sign(numerator) * Math.sign(denominator);
  const n = Math.abs(numerator);
  const d = Math.abs(denominator);
  return sign * Math.floor((2 * n + d) / (2 * d));
}

/** A share in basis points (1 bp = 0.01 %), rounded half up per line: 3 % of ETB 1,500.00 = ETB 45.00. */
export function percentage(a: Money, basisPoints: number): Money {
  if (!Number.isInteger(basisPoints)) throw new RangeError("basis points must be an integer");
  return money(divideHalfUp(a.amountMinor * basisPoints, 10_000), a.currency);
}

/**
 * Splits an amount by integer weights with no minor unit lost (largest remainder, earlier parts first on ties):
 * ETB 100.00 by [1, 1, 1] → 33.34, 33.33, 33.33.
 */
export function allocate(a: Money, weights: number[]): Money[] {
  if (weights.length === 0 || weights.some((w) => !Number.isInteger(w) || w < 0)) {
    throw new RangeError("weights must be non-negative integers");
  }
  const total = weights.reduce((s, w) => s + w, 0);
  if (total === 0) throw new RangeError("weights must not all be zero");
  const sign = a.amountMinor < 0 ? -1 : 1;
  const amount = Math.abs(a.amountMinor);
  const shares = weights.map((w) => Math.floor((amount * w) / total));
  let left = amount - shares.reduce((s, x) => s + x, 0);
  const order = weights
    .map((w, i) => ({ i, remainder: (amount * w) % total }))
    .sort((x, y) => y.remainder - x.remainder || x.i - y.i);
  for (const { i } of order) {
    if (left === 0) break;
    shares[i]! += 1;
    left -= 1;
  }
  return shares.map((s) => money(sign * s, a.currency));
}

/** Rounds to the smallest coin/note in circulation for cash payments (half up), e.g. KES to whole shillings. */
export function cashRound(a: Money): Money {
  const step = CURRENCIES[a.currency]?.cashRoundingMinor ?? 1;
  return step <= 1 ? a : money(divideHalfUp(a.amountMinor, step) * step, a.currency);
}

export const isZero = (a: Money) => a.amountMinor === 0;
export const isNegative = (a: Money) => a.amountMinor < 0;
export function compare(a: Money, b: Money): number {
  sameCurrency(a, b);
  return Math.sign(a.amountMinor - b.amountMinor);
}
