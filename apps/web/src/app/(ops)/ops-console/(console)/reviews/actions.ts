"use server";

import { problemText, staffApi } from "@/lib/ops/session";

export interface ReviewState {
  error?: string;
  done?: string;
}

/** Hide a review, put it back, or dismiss the reports on it; the reason is recorded. */
export async function decide(
  reviewId: string,
  _: ReviewState,
  form: FormData,
): Promise<ReviewState> {
  const decision = String(form.get("decision") ?? "") as "HIDE" | "RESTORE" | "DISMISS";
  const reason = String(form.get("reason") ?? "").trim();
  if (reason.length < 5) {
    return { error: "Give a reason (at least 5 characters); it is recorded." };
  }
  const { data, error } = await (
    await staffApi()
  ).POST("/v1/admin/reviews/{reviewId}/decision", {
    params: { path: { reviewId } },
    body: { decision, reason },
  });
  if (!data) return { error: problemText(error, "The decision was not saved.") };
  // No refresh: the confirmation stays until the next load.
  return { done: `Saved: ${data.status}` };
}
