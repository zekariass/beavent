import { createHmac } from "node:crypto";
import type { Refund, StubState, Transaction } from "./state.ts";

export interface WebhookConfig {
  url: string;
  secret: string;
}

/**
 * Chapa signs webhooks with HMAC-SHA256 using the webhook secret:
 *   x-chapa-signature = HMAC(secret, raw JSON body)
 *   chapa-signature   = HMAC(secret, secret)
 * (developer.chapa.co/integrations/webhooks). Verify against the raw body.
 */
export function sign(secret: string, payload: string): string {
  return createHmac("sha256", secret).update(payload).digest("hex");
}

export function transactionEvent(tx: Transaction) {
  const event =
    tx.status === "success"
      ? "charge.success"
      : tx.status === "cancelled"
        ? "charge.cancelled"
        : "charge.failed";
  return {
    event,
    first_name: tx.first_name ?? null,
    last_name: tx.last_name ?? null,
    email: tx.email ?? null,
    mobile: tx.mobile ?? null,
    currency: tx.currency,
    amount: tx.amount,
    charge: tx.charge,
    status: tx.status,
    ...(tx.failure_reason ? { failure_reason: tx.failure_reason } : {}),
    mode: tx.mode,
    reference: tx.reference,
    created_at: tx.created_at,
    updated_at: tx.updated_at,
    type: "API",
    tx_ref: tx.tx_ref,
    payment_method: tx.method,
    customization: tx.customization,
    meta: tx.meta,
  };
}

export function refundEvent(refund: Refund, tx: Transaction) {
  return {
    event: refund.status === "refunded" ? "charge.refunded" : "charge.reversed",
    type: "Refund",
    ref_id: refund.ref_id,
    merchant_reference: refund.merchant_reference,
    tx_ref: tx.tx_ref,
    payment_reference: tx.reference,
    amount: refund.amount,
    currency: refund.currency,
    status: refund.status,
    mode: tx.mode,
    created_at: refund.created_at,
    updated_at: refund.updated_at,
    meta: refund.meta,
  };
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Sends a webhook honouring the configured faults; retries a few times on failure. */
export async function deliver(
  state: StubState,
  config: WebhookConfig,
  txRef: string,
  body: Record<string, unknown>,
): Promise<void> {
  const { faults } = state;
  if (faults.dropWebhooks) return;
  if (faults.webhookDelayMs > 0) await sleep(faults.webhookDelayMs);
  const copies = faults.duplicateWebhooks ? 2 : 1;
  for (let copy = 0; copy < copies; copy += 1) {
    await send(state, config, txRef, body);
  }
}

async function send(
  state: StubState,
  config: WebhookConfig,
  txRef: string,
  body: Record<string, unknown>,
): Promise<void> {
  const payload = JSON.stringify(body);
  const headers = {
    "content-type": "application/json",
    "x-chapa-signature": sign(config.secret, payload),
    "chapa-signature": sign(config.secret, config.secret),
  };
  // Real Chapa retries every 10 minutes for 72 hours; the stub retries quickly.
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    let status: number | "error";
    try {
      const response = await fetch(config.url, { method: "POST", headers, body: payload });
      status = response.status;
    } catch {
      status = "error";
    }
    state.deliveries.push({
      event: String(body.event),
      tx_ref: txRef,
      attempt,
      status,
      delivered_at: new Date().toISOString(),
    });
    if (status === 200) return;
    await sleep(200 * attempt);
  }
}
