"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import type { AudienceCount, MarketingAudience } from "@beavent/venue";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, problemText } from "@/lib/business/session";

export interface MarketingState {
  error?: string;
  done?: string;
}

const base = (language: string, orgId: string) => `/${language}/business/o/${orgId}/marketing`;

/** Saves a campaign as a draft (a new one, or changes to the draft being edited) and opens it. */
export async function saveCampaign(
  language: string,
  orgId: string,
  campaignId: string | null,
  _: MarketingState,
  form: FormData,
): Promise<MarketingState> {
  const t = translator(routeLanguage(language));
  const days = Number(form.get("days") ?? "");
  const body = {
    name: String(form.get("name") ?? "").trim(),
    audience: String(form.get("audience") ?? "FOLLOWERS") as MarketingAudience,
    days: Number.isInteger(days) && days > 0 ? days : undefined,
    locationId: String(form.get("locationId") ?? "") || undefined,
    texts: {
      en: String(form.get("textEn") ?? ""),
      am: String(form.get("textAm") ?? ""),
    },
  };
  const api = await portalApi(language);
  const { data, error } = campaignId
    ? await api.PUT("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}", {
        params: { path: { orgId, campaignId } },
        body,
      })
    : await api.POST("/v1/orgs/{orgId}/marketing/campaigns", {
        params: { path: { orgId } },
        body,
      });
  if (!data) return { error: problemText(error, t("portal.marketing.error")) };
  revalidatePath(base(language, orgId));
  redirect(`${base(language, orgId)}/${data.id}`);
}

/** How many people a campaign to this audience would reach (the form shows it as it is filled in). */
export async function audienceCount(
  language: string,
  orgId: string,
  audience: MarketingAudience,
  days?: number,
): Promise<AudienceCount | null> {
  const { data } = await (
    await portalApi(language)
  ).GET("/v1/orgs/{orgId}/marketing/audience", {
    params: { path: { orgId }, query: { audience, days } },
  });
  return data ?? null;
}

/** Sends the campaign's message to the caller's own phone. */
export async function testCampaign(
  language: string,
  orgId: string,
  campaignId: string,
): Promise<MarketingState> {
  const t = translator(routeLanguage(language));
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}/test", {
    params: { path: { orgId, campaignId } },
  });
  return data
    ? { done: t("portal.marketing.testSent") }
    : { error: problemText(error, t("portal.marketing.error")) };
}

/** Sends the campaign now (`at` absent) or at a time. */
export async function scheduleCampaign(
  language: string,
  orgId: string,
  campaignId: string,
  at?: string,
): Promise<MarketingState> {
  const t = translator(routeLanguage(language));
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}/schedule", {
    params: { path: { orgId, campaignId } },
    body: at ? { at } : {},
  });
  if (!data) return { error: problemText(error, t("portal.marketing.error")) };
  revalidatePath(`${base(language, orgId)}/${campaignId}`);
  return {};
}

export async function cancelCampaign(
  language: string,
  orgId: string,
  campaignId: string,
): Promise<MarketingState> {
  const t = translator(routeLanguage(language));
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}/cancel", {
    params: { path: { orgId, campaignId } },
  });
  if (!data) return { error: problemText(error, t("portal.marketing.error")) };
  revalidatePath(`${base(language, orgId)}/${campaignId}`);
  return {};
}

export async function deleteCampaign(
  language: string,
  orgId: string,
  campaignId: string,
): Promise<MarketingState> {
  const t = translator(routeLanguage(language));
  const { response, error } = await (
    await portalApi(language)
  ).DELETE("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}", {
    params: { path: { orgId, campaignId } },
  });
  if (!response.ok) return { error: problemText(error, t("portal.marketing.error")) };
  revalidatePath(base(language, orgId));
  redirect(base(language, orgId));
}

/** Makes a counted link. */
export async function createLink(
  language: string,
  orgId: string,
  _: MarketingState,
  form: FormData,
): Promise<MarketingState> {
  const t = translator(routeLanguage(language));
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/marketing/links", {
    params: { path: { orgId } },
    body: {
      name: String(form.get("name") ?? "").trim(),
      locationId: String(form.get("locationId") ?? "") || undefined,
    },
  });
  if (!data) return { error: problemText(error, t("portal.marketing.error")) };
  revalidatePath(base(language, orgId));
  return {};
}

export async function deleteLink(
  language: string,
  orgId: string,
  linkId: string,
): Promise<MarketingState> {
  const t = translator(routeLanguage(language));
  const { response, error } = await (
    await portalApi(language)
  ).DELETE("/v1/orgs/{orgId}/marketing/links/{linkId}", {
    params: { path: { orgId, linkId } },
  });
  if (!response.ok) return { error: problemText(error, t("portal.marketing.error")) };
  revalidatePath(base(language, orgId));
  return {};
}
