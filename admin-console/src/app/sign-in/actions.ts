"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { MFA_COOKIE, cookieOptions, problemText, publicApi, storeTokens } from "@/lib/session";

export interface SignInState {
  step: "phone" | "code" | "mfa";
  phone?: string;
  challengeId?: string;
  error?: string;
}

/** Accepts local Ethiopian numbers (09…, 07…) as well as +251…. */
function normalizePhone(raw: string): string {
  const digits = raw.replace(/[^\d+]/g, "");
  if (/^0[79]\d{8}$/.test(digits)) return `+251${digits.slice(1)}`;
  return digits.startsWith("+") ? digits : `+${digits}`;
}

export async function requestCode(_: SignInState, form: FormData): Promise<SignInState> {
  const phone = normalizePhone(String(form.get("phone") ?? ""));
  const { data, error } = await publicApi().POST("/v1/auth/otp/request", {
    body: { phone, purpose: "SIGN_IN", channel: "SMS", clientApp: "ADMIN_CONSOLE", language: "en" },
  });
  if (!data) return { step: "phone", phone, error: problemText(error, "Could not send a code.") };
  return { step: "code", phone, challengeId: data.challengeId };
}

export async function verifyCode(state: SignInState, form: FormData): Promise<SignInState> {
  const { data, error } = await publicApi().POST("/v1/auth/otp/verify", {
    body: { challengeId: state.challengeId ?? "", code: String(form.get("code") ?? "").trim() },
  });
  if (!data)
    return { ...state, error: problemText(error, "The code is incorrect or has expired.") };
  if (data.result === "MFA_REQUIRED") {
    (await cookies()).set(MFA_COOKIE, data.mfaChallengeId, cookieOptions(300));
    return { step: "mfa", phone: state.phone };
  }
  // Signed in with the code only: the console will ask for an authenticator app first.
  await storeTokens(data.accessToken, data.refreshToken);
  redirect("/setup-authenticator");
}

export async function verifyAuthenticator(
  state: SignInState,
  form: FormData,
): Promise<SignInState> {
  const jar = await cookies();
  const mfaChallengeId = jar.get(MFA_COOKIE)?.value;
  if (!mfaChallengeId) return { step: "phone", error: "Your sign-in expired. Start again." };
  const { data, error } = await publicApi().POST("/v1/auth/mfa/verify", {
    body: { mfaChallengeId, code: String(form.get("code") ?? "").trim() },
  });
  if (!data || data.result !== "TOKENS") {
    return { ...state, error: problemText(error, "The code is incorrect.") };
  }
  jar.delete(MFA_COOKIE);
  await storeTokens(data.accessToken, data.refreshToken);
  redirect("/");
}
