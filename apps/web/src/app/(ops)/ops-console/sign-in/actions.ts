"use server";

import { redirect } from "next/navigation";
import { problemText, publicApi, storeTokens } from "@/lib/ops/session";
import { adminPath } from "@/lib/admin-path";

export interface SignInState {
  step: "identify" | "code";
  destination?: string;
  challengeId?: string;
  error?: string;
}

/** Accepts local Ethiopian numbers (09…, 07…) as well as +251…. */
function normalizePhone(raw: string): string {
  const digits = raw.replace(/[^\d+]/g, "");
  if (/^0[79]\d{8}$/.test(digits)) return `+251${digits.slice(1)}`;
  return digits.startsWith("+") ? digits : `+${digits}`;
}

/** Sends a one-time code to the staff member's phone (SMS) or email address. */
export async function requestCode(_: SignInState, form: FormData): Promise<SignInState> {
  const input = String(form.get("destination") ?? "").trim();
  const byEmail = input.includes("@");
  const destination = byEmail ? input.toLowerCase() : normalizePhone(input);
  const { data, error } = await publicApi().POST("/v1/auth/otp/request", {
    body: byEmail
      ? {
          email: destination,
          purpose: "SIGN_IN",
          channel: "EMAIL",
          clientApp: "ADMIN_CONSOLE",
          language: "en",
        }
      : {
          phone: destination,
          purpose: "SIGN_IN",
          channel: "SMS",
          clientApp: "ADMIN_CONSOLE",
          language: "en",
        },
  });
  if (!data) return { step: "identify", error: problemText(error, "Could not send a code.") };
  return { step: "code", destination, challengeId: data.challengeId };
}

export async function verifyCode(state: SignInState, form: FormData): Promise<SignInState> {
  const { data, error } = await publicApi().POST("/v1/auth/otp/verify", {
    body: { challengeId: state.challengeId ?? "", code: String(form.get("code") ?? "").trim() },
  });
  if (!data)
    return { ...state, error: problemText(error, "The code is incorrect or has expired.") };
  await storeTokens(data.accessToken, data.refreshToken);
  redirect(adminPath());
}
