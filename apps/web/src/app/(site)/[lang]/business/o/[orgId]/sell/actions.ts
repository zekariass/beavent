"use server";

import type { Schemas } from "@beavent/api-client";
import { portalApi, problemText } from "@/lib/business/session";

export type StartResult =
  { ok: true; orderId: string } | { ok: false; code?: string; message: string };

/** Starts a walk-in sale of goods: the order is made and the goods come off the stock. */
export async function startSale(
  language: string,
  orgId: string,
  locationId: string,
  items: { variantId: string; quantity: number }[],
): Promise<StartResult> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/sales", {
    params: { path: { orgId } },
    body: { locationId, items: items.filter((i) => i.quantity > 0) },
  });
  if (!data) {
    const code = (error as { code?: string } | undefined)?.code;
    return { ok: false, code, message: problemText(error) };
  }
  return { ok: true, orderId: data.orderId };
}

export type PayResult =
  { ok: true; done: Schemas["CheckoutDone"] } | { ok: false; code?: string; message: string };

/** Takes the payment: gift cards and the customer's credit first, then the cash; the sale is completed with a receipt. */
export async function paySale(
  language: string,
  orgId: string,
  orderId: string,
  pay: { giftCards: string[]; useBalance: boolean; cashTenderedMinor: number | null },
): Promise<PayResult> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/sales/{orderId}/payment", {
    params: { path: { orgId, orderId } },
    body: {
      ...(pay.giftCards.length > 0 ? { giftCards: pay.giftCards } : {}),
      ...(pay.useBalance ? { useBalance: true } : {}),
      ...(pay.cashTenderedMinor === null ? {} : { cashTenderedMinor: pay.cashTenderedMinor }),
    },
  });
  if (!data) {
    const code = (error as { code?: string } | undefined)?.code;
    return { ok: false, code, message: problemText(error) };
  }
  return { ok: true, done: data };
}

/** Cancels a sale nothing was paid on; the goods go back on the stock. */
export async function cancelSale(
  language: string,
  orgId: string,
  orderId: string,
): Promise<{ ok: boolean; message?: string }> {
  const { response, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/sales/{orderId}/cancel", {
    params: { path: { orgId, orderId } },
  });
  return response.ok ? { ok: true } : { ok: false, message: problemText(error) };
}
