// In-memory state for the Chapa stub. Everything resets on restart or POST /__stub/reset.

export type TxStatus = "pending" | "success" | "failed" | "cancelled";
export type RefundStatus = "initiated" | "processing" | "refunded" | "reversed";

export interface SplitInstruction {
  id: string;
  split_type: "percentage" | "flat";
  split_value: number;
}

export interface Transaction {
  tx_ref: string;
  reference: string; // Chapa's own reference
  amount: string; // decimal string, as Chapa accepts it
  currency: "ETB" | "USD";
  charge: string; // Chapa fee (stub: 0.00)
  status: TxStatus;
  mode: "test";
  method: string; // telebirr, cbebirr, mpesa, card, bank ... or "hosted"
  channel: "hosted" | "direct";
  first_name?: string;
  last_name?: string;
  email?: string;
  mobile?: string;
  callback_url?: string;
  return_url?: string;
  customization: Record<string, unknown>;
  meta: Record<string, unknown>;
  split?: SplitInstruction;
  platform_share?: string; // computed from the split
  seller_share?: string;
  failure_reason?: string;
  refunded_amount: string;
  created_at: string;
  updated_at: string;
}

export interface Refund {
  ref_id: string;
  tx_ref: string;
  merchant_reference: string;
  amount: string;
  currency: string;
  reason?: string;
  meta: Record<string, unknown>;
  status: RefundStatus;
  created_at: string;
  updated_at: string;
}

export interface Subaccount {
  subaccount_id: string;
  business_name: string;
  account_name: string;
  bank_code: string;
  account_number: string;
  split_type: "percentage" | "flat";
  split_value: number;
  created_at: string;
}

export interface WebhookDelivery {
  event: string;
  tx_ref: string;
  attempt: number;
  status: number | "error";
  delivered_at: string;
}

export interface Faults {
  /** Make /v1/transaction/initialize and /v1/charges fail with this HTTP status (e.g. 500, 503). */
  initializeError: number | null;
  /** Never send webhooks (forces the polling/verify path). */
  dropWebhooks: boolean;
  /** Send every webhook twice (idempotency). */
  duplicateWebhooks: boolean;
  /** Extra delay before webhooks are sent. */
  webhookDelayMs: number;
  /** Final status of refunds. */
  refundOutcome: "refunded" | "reversed";
}

export const defaultFaults = (): Faults => ({
  initializeError: null,
  dropWebhooks: false,
  duplicateWebhooks: false,
  webhookDelayMs: 0,
  refundOutcome: "refunded",
});

export class StubState {
  transactions = new Map<string, Transaction>();
  refunds = new Map<string, Refund>();
  refundReferences = new Set<string>();
  subaccounts = new Map<string, Subaccount>();
  deliveries: WebhookDelivery[] = [];
  faults: Faults = defaultFaults();
  private seq = 0;

  nextId(prefix: string): string {
    this.seq += 1;
    return `${prefix}${Date.now().toString(36).toUpperCase()}${this.seq.toString().padStart(4, "0")}`;
  }

  reset(): void {
    this.transactions.clear();
    this.refunds.clear();
    this.refundReferences.clear();
    this.subaccounts.clear();
    this.deliveries = [];
    this.faults = defaultFaults();
  }

  snapshot() {
    return {
      transactions: [...this.transactions.values()],
      refunds: [...this.refunds.values()],
      subaccounts: [...this.subaccounts.values()],
      deliveries: this.deliveries,
      faults: this.faults,
    };
  }
}
