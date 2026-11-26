"use server";

import { revalidatePath } from "next/cache";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, problemText } from "@/lib/business/session";

export interface ReviewState {
  error?: string;
  done?: string;
}

const refresh = (language: string, orgId: string) =>
  revalidatePath(`/${language}/business/o/${orgId}/reviews`);

/** Answers a review, or replaces the answer. */
export async function reply(
  language: string,
  orgId: string,
  reviewId: string,
  _: ReviewState,
  form: FormData,
): Promise<ReviewState> {
  const t = translator(routeLanguage(language));
  const body = String(form.get("body") ?? "").trim();
  if (!body) return { error: t("portal.reviews.replyLabel") };
  const { data, error } = await (
    await portalApi(language)
  ).PUT("/v1/orgs/{orgId}/reviews/{reviewId}/response", {
    params: { path: { orgId, reviewId } },
    body: { body },
  });
  if (!data) return { error: problemText(error, t("portal.reviews.error")) };
  refresh(language, orgId);
  return {};
}

/** Takes the answer back. */
export async function removeReply(
  language: string,
  orgId: string,
  reviewId: string,
): Promise<ReviewState> {
  const t = translator(routeLanguage(language));
  const { data, error } = await (
    await portalApi(language)
  ).DELETE("/v1/orgs/{orgId}/reviews/{reviewId}/response", {
    params: { path: { orgId, reviewId } },
  });
  if (!data) return { error: problemText(error, t("portal.reviews.error")) };
  refresh(language, orgId);
  return {};
}

/** Asks platform staff to look at a review. */
export async function report(
  language: string,
  orgId: string,
  reviewId: string,
  _: ReviewState,
  form: FormData,
): Promise<ReviewState> {
  const t = translator(routeLanguage(language));
  const reason = String(form.get("reason") ?? "OTHER") as
    "INAPPROPRIATE" | "HARASSMENT" | "MISLEADING_PRICE" | "UNSAFE" | "OTHER";
  const details = String(form.get("details") ?? "").trim();
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/reviews/{reviewId}/report", {
    params: { path: { orgId, reviewId } },
    body: { reason, details: details || undefined },
  });
  if (!data) return { error: problemText(error, t("portal.reviews.error")) };
  refresh(language, orgId);
  return { done: t("portal.reviews.reported") };
}
