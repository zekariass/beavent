"use server";

import { revalidatePath } from "next/cache";
import { OPS_INTERNAL } from "@/lib/admin-path";
import { problemText, staffApi } from "@/lib/ops/session";

export interface RefundState {
  error?: string;
  done?: string;
}

const text = (form: FormData, name: string) => String(form.get(name) ?? "").trim();

export async function approveRefund(
  refundId: string,
  _: RefundState,
  form: FormData,
): Promise<RefundState> {
  const note = text(form, "note");
  const { data, error } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/refunds/{refundId}/approval", {
    params: { path: { refundId } },
    body: { note: note || undefined },
  });
  if (!data) return { error: problemText(error, "It was not approved.") };
  revalidatePath(`${OPS_INTERNAL}/refunds`);
  return { done: `Saved: ${data.status}` };
}

export async function payRefund(
  refundId: string,
  _: RefundState,
  form: FormData,
): Promise<RefundState> {
  const reference = text(form, "reference");
  if (!reference) return { error: "Give the reference of the transfer you made." };
  const note = text(form, "note");
  const { data, error } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/refunds/{refundId}/payment", {
    params: { path: { refundId } },
    body: { reference, note: note || undefined },
  });
  if (!data) return { error: problemText(error, "It was not saved.") };
  revalidatePath(`${OPS_INTERNAL}/refunds`);
  return { done: `Saved: ${data.status}` };
}
