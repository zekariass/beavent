"use server";

import type { Schemas } from "@beavent/api-client";
import { revalidatePath } from "next/cache";
import {
  apiWithToken,
  clearCustomerTokens,
  customerApi,
  holdPendingTokens,
  needsName,
  normalizePhone,
  problemCode,
  problemText,
  publicApi,
  saveName,
  storeCustomerTokens,
  takePendingTokens,
} from "@/lib/customer/session";

export type Result<T> = { ok: true; value: T } | { ok: false; code?: string; message: string };

/** Step 1 of signing in on the public site: a code to the phone. */
export async function sendSignInCode(
  language: string,
  rawPhone: string,
): Promise<Result<{ challengeId: string; phone: string }>> {
  const phone = normalizePhone(rawPhone);
  const { data, error } = await publicApi(language).POST("/v1/auth/otp/request", {
    body: { phone, purpose: "SIGN_IN", channel: "SMS", clientApp: "WEB_MARKETPLACE", language },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  return { ok: true, value: { challengeId: data.challengeId, phone } };
}

/** Step 2: the code; the session goes into httpOnly cookies. */
export async function verifySignIn(
  language: string,
  challengeId: string,
  code: string,
): Promise<Result<{ needsName: boolean }>> {
  const { data, error } = await publicApi(language).POST("/v1/auth/otp/verify", {
    body: { challengeId, code: code.trim() },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  if (await needsName(apiWithToken(data.accessToken, language))) {
    await holdPendingTokens(data.accessToken, data.refreshToken);
    return { ok: true, value: { needsName: true } };
  }
  await storeCustomerTokens(data.accessToken, data.refreshToken);
  revalidatePath(`/${language}/account`, "layout");
  return { ok: true, value: { needsName: false } };
}

/** Step 3 for new people: what to call them (C-6); then the session held since the code starts. */
export async function saveCustomerName(
  language: string,
  given: string,
  father: string,
): Promise<Result<null>> {
  if (!given.trim()) return { ok: false, code: "NAME_NEEDED", message: "" };
  const pending = await takePendingTokens();
  if (!pending) return { ok: false, code: "UNAUTHENTICATED", message: "" };
  const error = await saveName(apiWithToken(pending.accessToken, language), given, father);
  if (error) {
    await holdPendingTokens(pending.accessToken, pending.refreshToken);
    return { ok: false, code: problemCode(error), message: problemText(error, "") };
  }
  await storeCustomerTokens(pending.accessToken, pending.refreshToken);
  revalidatePath(`/${language}/account`, "layout");
  return { ok: true, value: null };
}

export async function signOutCustomer(language: string) {
  const api = await customerApi(language);
  await api?.POST("/v1/auth/logout", { body: { allSessions: false } });
  await clearCustomerTokens();
  revalidatePath(`/${language}/account`, "layout");
}

export async function cancelAppointment(
  language: string,
  appointmentId: string,
): Promise<Result<Schemas["AppointmentDetail"]>> {
  const api = await customerApi(language);
  if (!api) return { ok: false, code: "UNAUTHENTICATED", message: "" };
  const { data, error } = await api.POST("/v1/me/appointments/{appointmentId}/cancel", {
    params: { path: { appointmentId } },
    body: {},
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  revalidatePath(`/${language}/account/bookings`, "layout");
  return { ok: true, value: data };
}

/**
 * Starts (or resumes) paying the deposit of an unpaid booking: the payment provider's page to send the person to. The
 * provider sends them back to the booking's payment page, which asks {@link checkPayment}.
 */
export async function startPayment(
  language: string,
  appointmentId: string,
): Promise<Result<Schemas["PaymentStart"]>> {
  const api = await customerApi(language);
  if (!api) return { ok: false, code: "UNAUTHENTICATED", message: "" };
  const { data, error } = await api.POST("/v1/me/appointments/{appointmentId}/payment", {
    params: { path: { appointmentId } },
    body: { language },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  return { ok: true, value: data };
}

/** Asks the provider where the payment stands (the person came back, or is waiting), then returns the booking. */
export async function checkPayment(
  language: string,
  appointmentId: string,
): Promise<Result<Schemas["AppointmentDetail"]>> {
  const api = await customerApi(language);
  if (!api) return { ok: false, code: "UNAUTHENTICATED", message: "" };
  const { data, error } = await api.POST("/v1/me/appointments/{appointmentId}/payment/check", {
    params: { path: { appointmentId } },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  if (data.status !== "PENDING_PAYMENT") revalidatePath(`/${language}/account/bookings`, "layout");
  return { ok: true, value: data };
}

export async function rescheduleAppointment(
  language: string,
  appointmentId: string,
  start: string,
): Promise<Result<Schemas["AppointmentDetail"]>> {
  const api = await customerApi(language);
  if (!api) return { ok: false, code: "UNAUTHENTICATED", message: "" };
  const { data, error } = await api.POST("/v1/me/appointments/{appointmentId}/reschedule", {
    params: { path: { appointmentId } },
    body: { start },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  revalidatePath(`/${language}/account/bookings`, "layout");
  return { ok: true, value: data };
}

/** Joins a place's waitlist (S-15). */
export async function joinWaitlist(
  language: string,
  body: Schemas["WaitlistJoin"],
): Promise<Result<null>> {
  const api = await customerApi(language);
  if (!api) return { ok: false, code: "UNAUTHENTICATED", message: "" };
  const { data, error } = await api.POST("/v1/me/waitlist", { body });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  revalidatePath(`/${language}/account/bookings`);
  return { ok: true, value: null };
}

/** Books the time a waitlist offer holds. */
export async function acceptWaitlistOffer(
  language: string,
  entryId: string,
): Promise<Result<{ appointmentId: string }>> {
  const api = await customerApi(language);
  if (!api) return { ok: false, code: "UNAUTHENTICATED", message: "" };
  const { data, error } = await api.POST("/v1/me/waitlist/{entryId}/accept", {
    params: { path: { entryId } },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  revalidatePath(`/${language}/account/bookings`, "layout");
  return { ok: true, value: { appointmentId: data.appointmentId } };
}

/** "Not this time" (decline) or leaving the waitlist altogether. */
export async function answerWaitlist(
  language: string,
  entryId: string,
  leave: boolean,
): Promise<Result<null>> {
  const api = await customerApi(language);
  if (!api) return { ok: false, code: "UNAUTHENTICATED", message: "" };
  const { response, error } = leave
    ? await api.DELETE("/v1/me/waitlist/{entryId}", { params: { path: { entryId } } })
    : await api.POST("/v1/me/waitlist/{entryId}/decline", { params: { path: { entryId } } });
  if (response.status !== 204)
    return { ok: false, code: problemCode(error), message: problemText(error, "") };
  revalidatePath(`/${language}/account/bookings`, "layout");
  return { ok: true, value: null };
}
