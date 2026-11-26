"use server";

import { revalidatePath } from "next/cache";
import { customerApi } from "@/lib/customer/session";

export type SavedResult = { ok: true } | { ok: false; signedOut: boolean };

/** Saves or unsaves a place for the signed-in customer. */
export async function setSaved(
  language: string,
  locationId: string,
  saved: boolean,
): Promise<SavedResult> {
  const api = await customerApi(language);
  if (!api) return { ok: false, signedOut: true };
  const params = { params: { path: { locationId } } };
  const { response } = saved
    ? await api.PUT("/v1/me/saved/locations/{locationId}", params)
    : await api.DELETE("/v1/me/saved/locations/{locationId}", params);
  if (response.status === 401) return { ok: false, signedOut: true };
  if (!response.ok) return { ok: false, signedOut: false };
  revalidatePath(`/${language}/account/saved`);
  return { ok: true };
}

/** Follows a business (or changes whether to be told about its news), or stops following it. */
export async function setFollowing(
  language: string,
  businessSlug: string,
  following: boolean,
  notifications?: boolean,
): Promise<SavedResult> {
  const api = await customerApi(language);
  if (!api) return { ok: false, signedOut: true };
  const params = { params: { path: { businessSlug } } };
  const { response } = following
    ? await api.PUT("/v1/me/follows/{businessSlug}", { ...params, body: { notifications } })
    : await api.DELETE("/v1/me/follows/{businessSlug}", params);
  if (response.status === 401) return { ok: false, signedOut: true };
  if (!response.ok) return { ok: false, signedOut: false };
  revalidatePath(`/${language}/account/following`);
  return { ok: true };
}
