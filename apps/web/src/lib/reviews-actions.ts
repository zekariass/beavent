"use server";

import { revalidatePath } from "next/cache";
import type { MyReview, ReviewPage } from "@beavent/venue";
import { customerApi } from "@/lib/customer/session";
import { loadReviews } from "@/lib/reviews";

export type ReviewResult =
  { ok: true; review?: MyReview } | { ok: false; signedOut: boolean; late?: boolean };

/** The next page of a business's (or place's) reviews, for "Show more". */
export async function moreReviews(
  language: string,
  business: string,
  location: string | undefined,
  cursor: string,
): Promise<ReviewPage | null> {
  return loadReviews(language, business, { location, cursor, limit: 10 });
}

function failure(status: number): ReviewResult {
  return { ok: false, signedOut: status === 401, late: status === 422 };
}

/** Reviews a completed visit. */
export async function writeReview(
  language: string,
  appointmentId: string,
  rating: number,
  body: string,
): Promise<ReviewResult> {
  const api = await customerApi(language);
  if (!api) return { ok: false, signedOut: true };
  const { data, response } = await api.POST("/v1/me/appointments/{appointmentId}/review", {
    params: { path: { appointmentId } },
    body: { rating, body: body.trim() || undefined },
  });
  if (!data) return failure(response.status);
  revalidatePath(`/${language}/account/reviews`);
  return { ok: true, review: data };
}

/** Changes the rating or the words of the person's own review. */
export async function changeReview(
  language: string,
  reviewId: string,
  rating: number,
  body: string,
): Promise<ReviewResult> {
  const api = await customerApi(language);
  if (!api) return { ok: false, signedOut: true };
  const { data, response } = await api.PUT("/v1/me/reviews/{reviewId}", {
    params: { path: { reviewId } },
    body: { rating, body: body.trim() || undefined },
  });
  if (!data) return failure(response.status);
  revalidatePath(`/${language}/account/reviews`);
  return { ok: true, review: data };
}

/** Deletes the person's own review. */
export async function removeReview(language: string, reviewId: string): Promise<ReviewResult> {
  const api = await customerApi(language);
  if (!api) return { ok: false, signedOut: true };
  const { response } = await api.DELETE("/v1/me/reviews/{reviewId}", {
    params: { path: { reviewId } },
  });
  if (!response.ok) return failure(response.status);
  revalidatePath(`/${language}/account/reviews`);
  return { ok: true };
}
