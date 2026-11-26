"use server";

import { revalidatePath } from "next/cache";
import { OPS_INTERNAL } from "@/lib/admin-path";
import { problemText, staffApi } from "@/lib/ops/session";

export interface ProviderState {
  error?: string;
  done?: string;
}

const text = (form: FormData, name: string) => String(form.get(name) ?? "").trim();

export async function goLive(_: ProviderState, form: FormData): Promise<ProviderState> {
  const evidence = text(form, "evidence");
  if (evidence.length < 10) {
    return { error: "Say what the live payment, refund and payout were (references)." };
  }
  const { data, error } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/payment-provider/go-live", { body: { evidence } });
  if (!data) return { error: problemText(error, "It was not made live.") };
  revalidatePath(`${OPS_INTERNAL}/provider`);
  return { done: `The provider is ${data.status}.` };
}

export async function suspend(_: ProviderState, form: FormData): Promise<ProviderState> {
  const reason = text(form, "reason");
  if (reason.length < 3) return { error: "Say why." };
  const { data, error } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/payment-provider/suspension", { body: { reason } });
  if (!data) return { error: problemText(error, "It was not suspended.") };
  revalidatePath(`${OPS_INTERNAL}/provider`);
  return { done: `The provider is ${data.status}.` };
}
