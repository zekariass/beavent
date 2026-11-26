"use server";

import type { Schemas } from "@beavent/api-client";
import { portalApi, problemText } from "@/lib/business/session";

export type CheckoutResult =
  { ok: true; done: Schemas["CheckoutDone"] } | { ok: false; code?: string; message: string };

export interface SaleInput {
  tips: { staffId: string; amountMinor: number }[];
  discount: { kind: "AMOUNT" | "PERCENT"; value: number; reason: string } | null;
  giftCards: string[];
  useBalance: boolean;
  cashTenderedMinor: number | null;
}

/**
 * Checks the visit out: a discount, tips for the team, gift cards and the customer's credit, then the cash for the rest;
 * the visit is completed and the receipt issued.
 */
export async function checkoutVisit(
  language: string,
  orgId: string,
  appointmentId: string,
  sale: SaleInput,
): Promise<CheckoutResult> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/appointments/{appointmentId}/checkout", {
    params: { path: { orgId, appointmentId } },
    body: {
      tips: sale.tips.filter((t) => t.amountMinor > 0),
      ...(sale.discount ? { discount: sale.discount } : {}),
      ...(sale.giftCards.length > 0 ? { giftCards: sale.giftCards } : {}),
      ...(sale.useBalance ? { useBalance: true } : {}),
      ...(sale.cashTenderedMinor === null ? {} : { cashTenderedMinor: sale.cashTenderedMinor }),
    },
  });
  if (!data) {
    const code = (error as { code?: string } | undefined)?.code;
    return { ok: false, code, message: problemText(error) };
  }
  return { ok: true, done: data };
}

/** Sends the customer a link to their receipt: by text message, or email without a phone number. */
export async function sendReceipt(
  language: string,
  orgId: string,
  orderId: string,
): Promise<{ ok: true; via: string } | { ok: false; code?: string; message: string }> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/orders/{orderId}/receipt/send", {
    params: { path: { orgId, orderId } },
  });
  if (!data) {
    const code = (error as { code?: string } | undefined)?.code;
    return { ok: false, code, message: problemText(error) };
  }
  return { ok: true, via: data.via };
}
