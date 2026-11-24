"use server";

import { revalidatePath } from "next/cache";
import { problemText, staffApi } from "@/lib/ops/session";
import { OPS_INTERNAL } from "@/lib/admin-path";

export interface DecisionState {
  error?: string;
  done?: string;
}

export async function decide(
  caseId: string,
  _: DecisionState,
  form: FormData,
): Promise<DecisionState> {
  const decision = String(form.get("decision") ?? "") as "APPROVE" | "REJECT" | "REQUEST_INFO";
  const reason = String(form.get("reason") ?? "").trim();
  if (reason.length < 5)
    return { error: "Give a reason (at least 5 characters); it is recorded in the audit log." };
  const { data, error } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/verification-cases/{caseId}/decision", {
    params: { path: { caseId } },
    body: { decision, reason },
  });
  if (!data) return { error: problemText(error, "The decision was not saved.") };
  revalidatePath(`${OPS_INTERNAL}/verification/${caseId}`);
  return { done: `Saved: ${data.verificationCase.status}` };
}
