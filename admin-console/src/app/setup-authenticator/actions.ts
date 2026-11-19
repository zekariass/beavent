"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ACCESS_COOKIE, REFRESH_COOKIE, problemText, staffApi } from "@/lib/session";

export interface EnrollState {
  factorId?: string;
  secret?: string;
  otpauthUri?: string;
  error?: string;
}

export async function startEnrollment(): Promise<EnrollState> {
  const { data, error } = await (await staffApi()).POST("/v1/me/mfa/totp");
  if (!data) return { error: problemText(error, "Could not start set-up.") };
  return { factorId: data.factorId, secret: data.secret, otpauthUri: data.otpauthUri };
}

export async function confirmEnrollment(state: EnrollState, form: FormData): Promise<EnrollState> {
  const { error, response } = await (
    await staffApi()
  ).POST("/v1/me/mfa/totp/confirm", {
    body: { factorId: state.factorId ?? "", code: String(form.get("code") ?? "").trim() },
  });
  if (!response.ok) return { ...state, error: problemText(error, "The code is incorrect.") };
  // Sign in again so the new session is a multi-factor one.
  const jar = await cookies();
  jar.delete(ACCESS_COOKIE);
  jar.delete(REFRESH_COOKIE);
  redirect("/sign-in?enrolled=1");
}
