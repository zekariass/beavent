// Server-only: what the signed-in customer chose about reminders and quiet hours.
import type { NotificationSettings } from "@beavent/venue";
import { customerApi } from "@/lib/customer/session";

/** The person's settings; `signedIn` is false when nobody is signed in or the session ended. */
export async function loadNotificationSettings(
  language: string,
): Promise<{ signedIn: boolean; settings: NotificationSettings | null }> {
  const api = await customerApi(language);
  const result = api ? await api.GET("/v1/me/notification-settings").catch(() => null) : null;
  return result?.data
    ? { signedIn: true, settings: result.data }
    : { signedIn: false, settings: null };
}
