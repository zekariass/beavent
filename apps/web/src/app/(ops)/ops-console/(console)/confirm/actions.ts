"use server";

import { cookies } from "next/headers";
import {
  REFRESH_COOKIE,
  STEP_UP_COOKIE,
  cookieOptions,
  problemText,
  secondsLeft,
  staffApi,
} from "@/lib/ops/session";

export interface ConfirmState {
  challengeId?: string;
  sentTo?: string;
  error?: string;
  confirmed?: boolean;
}

/** Sends a fresh code to the staff member's verified phone (or, without one, their verified email). */
export async function sendConfirmationCode(): Promise<ConfirmState> {
  const api = await staffApi();
  const { data: me } = await api.GET("/v1/me");
  if (!me) return { error: "Please sign in again." };
  const body =
    me.phone && me.phoneVerified
      ? {
          phone: me.phone,
          purpose: "STEP_UP" as const,
          channel: "SMS" as const,
          clientApp: "ADMIN_CONSOLE" as const,
        }
      : me.email && me.emailVerified
        ? {
            email: me.email,
            purpose: "STEP_UP" as const,
            channel: "EMAIL" as const,
            clientApp: "ADMIN_CONSOLE" as const,
          }
        : null;
  if (!body) return { error: "Your account has no verified phone or email." };
  const { data, error } = await api.POST("/v1/auth/otp/request", { body });
  if (!data) return { error: problemText(error, "Could not send a code.") };
  return { challengeId: data.challengeId, sentTo: "phone" in body ? body.phone : body.email };
}

export async function confirmCode(state: ConfirmState, form: FormData): Promise<ConfirmState> {
  const { data, error } = await (
    await staffApi()
  ).POST("/v1/auth/step-up", {
    body: { challengeId: state.challengeId ?? "", code: String(form.get("code") ?? "").trim() },
  });
  if (!data)
    return { ...state, error: problemText(error, "The code is incorrect or has expired.") };
  const jar = await cookies();
  jar.set(
    STEP_UP_COOKIE,
    data.accessToken,
    cookieOptions(Math.max(secondsLeft(data.accessToken), 30)),
  );
  // Step-up rotates the session: keep the new refresh token, or the next refresh would look like token reuse.
  jar.set(REFRESH_COOKIE, data.refreshToken, cookieOptions(60 * 60 * 12));
  return { confirmed: true };
}
