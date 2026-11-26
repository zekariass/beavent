"use server";

import type { NotificationSettings } from "@beavent/venue";
import { customerApi } from "@/lib/customer/session";

export type SettingsResult = { ok: true } | { ok: false; signedOut: boolean };

/** Saves the signed-in customer's choices about reminders and quiet hours. */
export async function saveNotificationSettings(
  language: string,
  settings: NotificationSettings,
): Promise<SettingsResult> {
  const api = await customerApi(language);
  if (!api) return { ok: false, signedOut: true };
  const { response } = await api.PUT("/v1/me/notification-settings", { body: settings });
  if (response.status === 401) return { ok: false, signedOut: true };
  return response.ok ? { ok: true } : { ok: false, signedOut: false };
}
