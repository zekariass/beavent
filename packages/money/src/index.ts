/** Money is always an integer amount of minor units plus an ISO 4217 currency code (docs/05 §2). */
export interface Money {
  amountMinor: number;
  currency: string;
}
