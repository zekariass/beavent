import type { Metadata } from "next";
import { connection } from "next/server";
import { PhoneSignIn } from "@/components/customer/PhoneSignIn";
import { NotificationSettingsForm } from "@/components/customer/NotificationSettingsForm";
import { SiteHeader } from "@/components/SiteHeader";
import { loadNotificationSettings } from "@/lib/customer/notification-settings";
import { routeLanguage, translator } from "@/lib/i18n";

export const metadata: Metadata = { robots: { index: false, follow: false } };

/** Notifications: which channels carry reminders and review requests, and quiet hours. */
export default async function NotificationsPage({
  params,
}: PageProps<"/[lang]/account/notifications">) {
  await connection();
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  const { signedIn, settings } = await loadNotificationSettings(language);
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path="/account/notifications" />
      <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 pb-12 sm:px-6">
        <h1 className="bv-type-title1 text-fg">{t("notifications.title")}</h1>
        {!signedIn || !settings ? (
          <section className="flex flex-col items-start gap-3">
            <p className="bv-type-body max-w-prose text-fg-muted">{t("notifications.signedOut")}</p>
            <PhoneSignIn language={language} />
          </section>
        ) : (
          <>
            <p className="bv-type-body max-w-prose text-fg-muted">{t("notifications.intro")}</p>
            <NotificationSettingsForm language={language} initial={settings} />
          </>
        )}
      </main>
    </div>
  );
}
