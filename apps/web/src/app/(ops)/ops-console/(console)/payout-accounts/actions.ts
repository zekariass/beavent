"use server";

import { revalidatePath } from "next/cache";
import { OPS_INTERNAL } from "@/lib/admin-path";
import { problemText, staffApi } from "@/lib/ops/session";

export interface ReviewState {
  error?: string;
  done?: string;
  /** The full number, shown once to the reviewer after a reveal. */
  accountNumber?: string;
}

const reasonOf = (form: FormData) => String(form.get("reason") ?? "").trim();
const TOO_SHORT = "Give a reason (at least 5 characters); it is recorded in the audit log.";

export async function reveal(
  accountId: string,
  _: ReviewState,
  form: FormData,
): Promise<ReviewState> {
  const reason = reasonOf(form);
  if (reason.length < 5) return { error: TOO_SHORT };
  const { data, error } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/payout-accounts/{accountId}/reveal", {
    params: { path: { accountId } },
    body: { reason },
  });
  if (!data) return { error: problemText(error, "The number could not be shown.") };
  return { accountNumber: data.accountNumber };
}

export async function decide(
  accountId: string,
  _: ReviewState,
  form: FormData,
): Promise<ReviewState> {
  const decision = String(form.get("decision") ?? "") as "APPROVE" | "REJECT";
  const reason = reasonOf(form);
  if (reason.length < 5) return { error: TOO_SHORT };
  const { data, error } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/payout-accounts/{accountId}/decision", {
    params: { path: { accountId } },
    body: { decision, reason },
  });
  if (!data) return { error: problemText(error, "The decision was not saved.") };
  revalidatePath(`${OPS_INTERNAL}/payout-accounts`);
  return { done: `Saved: ${data.status}` };
}
