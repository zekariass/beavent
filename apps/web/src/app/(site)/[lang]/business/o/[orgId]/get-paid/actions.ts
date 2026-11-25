"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { routeLanguage, translator } from "@/lib/i18n";
import {
  API_URL,
  REFRESH_COOKIE,
  cookieOptions,
  portalApi,
  problemText,
} from "@/lib/business/session";
import { createApiClient } from "@beavent/api-client";

/** Two steps in one form: the first send a fresh code, the second confirms it and makes the change. */
export interface PayoutState {
  challengeId?: string;
  sentTo?: string;
  error?: string;
  done?: boolean;
  /** What was typed in the first step, kept for the second. */
  values?: { institutionCode: string; accountName: string; accountNumber: string };
}

/** Sends a fresh code to the signed-in person's verified phone (or, without one, their verified email). */
async function sendCode(language: string) {
  const t = translator(routeLanguage(language));
  const api = await portalApi(language);
  const { data: me } = await api.GET("/v1/me");
  if (!me) return { error: t("getPaid.error.session") };
  const body =
    me.phone && me.phoneVerified
      ? {
          phone: me.phone,
          purpose: "STEP_UP" as const,
          channel: "SMS" as const,
          clientApp: "PARTNER_PORTAL" as const,
          language,
        }
      : me.email && me.emailVerified
        ? {
            email: me.email,
            purpose: "STEP_UP" as const,
            channel: "EMAIL" as const,
            clientApp: "PARTNER_PORTAL" as const,
            language,
          }
        : null;
  if (!body) return { error: t("getPaid.error.noContact") };
  const { data, error } = await api.POST("/v1/auth/otp/request", { body });
  if (!data) return { error: problemText(error, t("getPaid.error.sendCode")) };
  return { challengeId: data.challengeId, sentTo: "phone" in body ? body.phone : body.email };
}

/** Confirms the code; the short-lived token is used for this one change and never stored. */
async function confirm(language: string, challengeId: string, code: string) {
  const t = translator(routeLanguage(language));
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/auth/step-up", { body: { challengeId, code } });
  if (!data) return { error: problemText(error, t("getPaid.error.code")) };
  // Step-up rotates the session: keep the new refresh token, or the next refresh would look like token reuse.
  (await cookies()).set(REFRESH_COOKIE, data.refreshToken, cookieOptions(60 * 60 * 24 * 30));
  return { token: data.accessToken };
}

const clientWith = (token: string, language: string) =>
  createApiClient({ baseUrl: API_URL, getAccessToken: () => token, getLanguage: () => language });

export async function addPayoutAccount(
  language: string,
  orgId: string,
  state: PayoutState,
  form: FormData,
): Promise<PayoutState> {
  const t = translator(routeLanguage(language));
  if (!state.challengeId) {
    const values = {
      institutionCode: String(form.get("institutionCode") ?? ""),
      accountName: String(form.get("accountName") ?? "").trim(),
      accountNumber: String(form.get("accountNumber") ?? "").trim(),
    };
    if (!values.institutionCode || !values.accountName || !values.accountNumber)
      return { values, error: t("getPaid.error.missing") };
    const sent = await sendCode(language);
    return "error" in sent && sent.error ? { values, error: sent.error } : { values, ...sent };
  }
  const code = String(form.get("code") ?? "").trim();
  const confirmed = await confirm(language, state.challengeId, code);
  if (!("token" in confirmed)) return { ...state, error: confirmed.error };
  const { data, error } = await clientWith(confirmed.token!, language).POST(
    "/v1/orgs/{orgId}/payout-accounts",
    { params: { path: { orgId } }, body: state.values! },
  );
  if (!data) return { values: state.values, error: problemText(error, t("getPaid.error.save")) };
  revalidatePath(`/${language}/business/o/${orgId}/get-paid`);
  return { done: true };
}

export async function withdrawPayoutAccount(
  language: string,
  orgId: string,
  accountId: string,
  state: PayoutState,
  form: FormData,
): Promise<PayoutState> {
  const t = translator(routeLanguage(language));
  if (!state.challengeId) {
    const sent = await sendCode(language);
    return "error" in sent && sent.error ? { error: sent.error } : sent;
  }
  const confirmed = await confirm(
    language,
    state.challengeId,
    String(form.get("code") ?? "").trim(),
  );
  if (!("token" in confirmed)) return { ...state, error: confirmed.error };
  const { response, error } = await clientWith(confirmed.token!, language).DELETE(
    "/v1/orgs/{orgId}/payout-accounts/{accountId}",
    { params: { path: { orgId, accountId } } },
  );
  if (!response.ok) return { ...state, error: problemText(error, t("getPaid.error.save")) };
  revalidatePath(`/${language}/business/o/${orgId}/get-paid`);
  return { done: true };
}
