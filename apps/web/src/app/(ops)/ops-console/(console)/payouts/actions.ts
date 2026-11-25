"use server";

import { revalidatePath } from "next/cache";
import { OPS_INTERNAL } from "@/lib/admin-path";
import { problemText, staffApi } from "@/lib/ops/session";

export interface PayoutState {
  error?: string;
  done?: string;
}

const text = (form: FormData, name: string) => String(form.get(name) ?? "").trim();

export async function decidePayout(
  payoutId: string,
  _: PayoutState,
  form: FormData,
): Promise<PayoutState> {
  const reason = text(form, "reason");
  if (reason.length < 5)
    return { error: "Give a reason (at least 5 characters); it is recorded in the audit log." };
  const { data, error } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/payouts/{payoutId}/decision", {
    params: { path: { payoutId } },
    body: { decision: text(form, "decision") === "REJECT" ? "REJECT" : "APPROVE", reason },
  });
  if (!data) return { error: problemText(error, "The decision was not saved.") };
  revalidatePath(`${OPS_INTERNAL}/payouts`);
  return { done: `Saved: ${data.status}` };
}

export async function settlePayout(
  payoutId: string,
  _: PayoutState,
  form: FormData,
): Promise<PayoutState> {
  const outcome = text(form, "outcome") as "PAID" | "FAILED" | "RETURNED";
  const reference = text(form, "reference");
  const reason = text(form, "reason");
  if (outcome === "PAID" && !reference)
    return { error: "Give the bank's reference for the payment." };
  if (outcome !== "PAID" && reason.length < 5)
    return { error: "Say why (at least 5 characters); the business is told." };
  const { data, error } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/payouts/{payoutId}/settlement", {
    params: { path: { payoutId } },
    body: { outcome, reference: reference || undefined, reason: reason || undefined },
  });
  if (!data) return { error: problemText(error, "It was not saved.") };
  revalidatePath(`${OPS_INTERNAL}/payouts`);
  return { done: `Saved: ${data.status}` };
}
