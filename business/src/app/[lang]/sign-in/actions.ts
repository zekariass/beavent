"use server";

import { redirect } from "next/navigation";
import { problemText, publicApi, storeTokens } from "@/lib/session";

export interface SignInState {
  step: "identify" | "code";
  destination?: string;
  challengeId?: string;
  error?: string;
}

function normalizePhone(raw: string): string {
  const digits = raw.replace(/[^\d+]/g, "");
  if (/^0[79]\d{8}$/.test(digits)) return `+251${digits.slice(1)}`;
  return digits.startsWith("+") ? digits : `+${digits}`;
}

export async function signInStep(
  language: string,
  state: SignInState,
  form: FormData,
): Promise<SignInState> {
  const api = publicApi(language);
  if (state.step === "identify") {
    const input = String(form.get("destination") ?? "").trim();
    const byEmail = input.includes("@");
    const destination = byEmail ? input.toLowerCase() : normalizePhone(input);
    const { data, error } = await api.POST("/v1/auth/otp/request", {
      body: byEmail
        ? {
            email: destination,
            purpose: "SIGN_IN",
            channel: "EMAIL",
            clientApp: "PARTNER_PORTAL",
            language,
          }
        : {
            phone: destination,
            purpose: "SIGN_IN",
            channel: "SMS",
            clientApp: "PARTNER_PORTAL",
            language,
          },
    });
    if (!data) return { step: "identify", error: problemText(error) };
    return { step: "code", destination, challengeId: data.challengeId };
  }
  const { data, error } = await api.POST("/v1/auth/otp/verify", {
    body: { challengeId: state.challengeId ?? "", code: String(form.get("code") ?? "").trim() },
  });
  if (!data) return { ...state, error: problemText(error) };
  await storeTokens(data.accessToken, data.refreshToken);
  redirect(`/${language}/orgs`);
}
