"use server";

import { problemText, staffApi } from "@/lib/ops/session";

export interface MarketplaceState {
  error?: string;
  done?: string;
}

/** List a business, send its request back, or take it off the marketplace; the reason is audited. */
export async function decide(
  orgId: string,
  _: MarketplaceState,
  form: FormData,
): Promise<MarketplaceState> {
  const decision = String(form.get("decision") ?? "") as "LIST" | "REJECT" | "DELIST";
  const reason = String(form.get("reason") ?? "").trim();
  if (reason.length < 5) {
    return { error: "Give a reason (at least 5 characters); it is recorded in the audit log." };
  }
  const { data, error } = await (
    await staffApi()
  ).POST("/v1/admin/orgs/{orgId}/marketplace-decision", {
    params: { path: { orgId } },
    body: { decision, reason },
  });
  if (!data) return { error: problemText(error, "The decision was not saved.") };
  // No refresh here: the business leaves this tab with the decision, and the confirmation should stay until the next load.
  return { done: `Saved: ${data.status}` };
}
