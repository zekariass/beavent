"use server";

import type { Schemas } from "@beavent/api-client";
import {
  apiWithToken,
  customerApi,
  needsName,
  normalizePhone,
  problemCode,
  problemText,
  publicApi,
  saveName,
  storeCustomerTokens,
} from "@/lib/customer/session";

export interface ItemChoice {
  variantId: string;
  addonIds: string[];
  staffId?: string;
}

export type Failure = { ok: false; code?: string; message: string };

const items = (choices: ItemChoice[]) =>
  choices.map((c) => ({
    variantId: c.variantId,
    addonIds: c.addonIds,
    ...(c.staffId ? { staffId: c.staffId } : {}),
  }));

/** Bookable start times between two local dates (the location's zone). */
export async function findTimes(
  locationId: string,
  choices: ItemChoice[],
  from: string,
  to: string,
): Promise<{ ok: true; slots: Schemas["AvailabilitySlot"][] } | Failure> {
  const { data, error } = await publicApi().POST("/v1/public/locations/{locationId}/availability", {
    params: { path: { locationId } },
    body: { items: items(choices), from, to },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  return { ok: true, slots: data.slots };
}

/** Holds the chosen start for a few minutes while the person reviews and signs in. */
export async function holdTime(
  locationId: string,
  choices: ItemChoice[],
  start: string,
): Promise<{ ok: true; hold: Schemas["Hold"] } | Failure> {
  const { data, error } = await publicApi().POST("/v1/public/locations/{locationId}/holds", {
    params: { path: { locationId } },
    body: { items: items(choices), start },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  return { ok: true, hold: data };
}

/** Lets go of a hold when the person goes back to choose another time. */
export async function releaseHold(locationId: string, holdId: string, holderKey: string) {
  await publicApi().DELETE("/v1/public/locations/{locationId}/holds/{holdId}", {
    params: { path: { locationId, holdId }, header: { "X-Hold-Key": holderKey } },
  });
}

/** The phone of the signed-in customer on this browser, or `null`. */
export async function signedInPhone(): Promise<string | null> {
  const api = await customerApi();
  if (!api) return null;
  const { data } = await api.GET("/v1/me");
  return data?.phone ?? data?.email ?? null;
}

/** Sends a one-time code (inline sign-in, DD10). */
export async function sendCode(
  language: string,
  rawPhone: string,
): Promise<{ ok: true; challengeId: string; phone: string } | Failure> {
  const phone = normalizePhone(rawPhone);
  const { data, error } = await publicApi(language).POST("/v1/auth/otp/request", {
    body: { phone, purpose: "SIGN_IN", channel: "SMS", clientApp: "WEB_MARKETPLACE", language },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  return { ok: true, challengeId: data.challengeId, phone };
}

/**
 * Books the held time. Without a session, `challengeId` + `code` sign the person in first (the session is kept in
 * httpOnly cookies for their next visit). Someone without a name on file gets `NAME_NEEDED` (signed in by then) and
 * books again with `givenName` (C-6 step 3), so the business sees a name, not a phone number.
 */
export async function book(
  language: string,
  input: {
    locationId: string;
    holdId: string;
    holderKey: string;
    note?: string;
    challengeId?: string;
    code?: string;
    givenName?: string;
    fatherName?: string;
  },
): Promise<{ ok: true; appointment: Schemas["Appointment"] } | Failure> {
  let api = await customerApi(language);
  if (input.challengeId && input.code) {
    const { data, error } = await publicApi(language).POST("/v1/auth/otp/verify", {
      body: { challengeId: input.challengeId, code: input.code.trim() },
    });
    if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
    await storeCustomerTokens(data.accessToken, data.refreshToken);
    api = apiWithToken(data.accessToken, language);
  }
  if (!api) return { ok: false, code: "UNAUTHENTICATED", message: "" };
  if (input.givenName?.trim()) {
    const problem = await saveName(api, input.givenName, input.fatherName ?? "");
    if (problem)
      return { ok: false, code: problemCode(problem), message: problemText(problem, "") };
  } else if (await needsName(api)) {
    return { ok: false, code: "NAME_NEEDED", message: "" };
  }
  const { data, error } = await api.POST("/v1/me/appointments", {
    body: {
      locationId: input.locationId,
      holdId: input.holdId,
      holderKey: input.holderKey,
      ...(input.note?.trim() ? { note: input.note.trim() } : {}),
    },
  });
  if (!data) return { ok: false, code: problemCode(error), message: problemText(error, "") };
  return { ok: true, appointment: data };
}
