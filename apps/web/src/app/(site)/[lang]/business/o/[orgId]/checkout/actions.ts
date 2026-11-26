"use server";

import type { Schemas } from "@beavent/api-client";
import { portalApi, problemText } from "@/lib/business/session";

export type CheckoutResult =
  { ok: true; done: Schemas["CheckoutDone"] } | { ok: false; code?: string; message: string };

/** Takes the cash for a visit: tips for the team, the drawer, the visit completed and a receipt, all together. */
export async function checkoutVisit(
  language: string,
  orgId: string,
  appointmentId: string,
  tips: { staffId: string; amountMinor: number }[],
  cashTenderedMinor: number | null,
): Promise<CheckoutResult> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/appointments/{appointmentId}/checkout", {
    params: { path: { orgId, appointmentId } },
    body: {
      tips: tips.filter((t) => t.amountMinor > 0),
      ...(cashTenderedMinor === null ? {} : { cashTenderedMinor }),
    },
  });
  if (!data) {
    const code = (error as { code?: string } | undefined)?.code;
    return { ok: false, code, message: problemText(error) };
  }
  return { ok: true, done: data };
}
