import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { calendarLanguage } from "@beavent/venue";
import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { SiteHeader } from "@/components/SiteHeader";
import { customerApi } from "@/lib/customer/session";
import { routeLanguage } from "@/lib/i18n";
import { PaymentStatus } from "./PaymentStatus";

export const metadata: Metadata = { robots: { index: false, follow: false } };

/**
 * Where the payment provider sends the person back to, and where an unpaid booking is paid from (the booking's
 * detail, the waitlist). `?result=` is what the provider's redirect said; it is never proof: the page asks Beavent,
 * which asks the provider.
 */
export default async function PaymentPage({
  params,
  searchParams,
}: PageProps<"/[lang]/account/bookings/[appointmentId]/payment">) {
  await connection();
  const { lang, appointmentId } = await params;
  const { result } = await searchParams;
  const language = routeLanguage(lang);
  const api = await customerApi(language);
  if (!api) redirect(`/${language}/account/bookings`);
  const { data: a, response } = await api.GET("/v1/me/appointments/{appointmentId}", {
    params: { path: { appointmentId } },
  });
  if (response.status === 401) redirect(`/${language}/account/bookings`);
  if (!a) notFound();
  const prefs = defaultDisplayPreferences(calendarLanguage(language), a.timezone);
  const outcome =
    result === "success" || result === "cancel" || result === "error" ? result : "pending";
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/account/bookings/${a.id}/payment`} />
      <main className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-6 px-4 py-8 sm:px-6">
        <PaymentStatus
          language={language}
          outcome={outcome}
          initial={a}
          when={{
            date: formatDualDate(a.start, prefs, { weekday: true }),
            time: formatDualTime(a.start, prefs),
          }}
          venueHref={
            a.place ? `/${language}/b/${a.place.businessSlug}/${a.place.locationSlug}/book` : null
          }
          heldUntil={
            a.payment?.expiresAt ? formatDualTime(a.payment.expiresAt, prefs).primary : null
          }
          what={[a.place?.businessName, a.booking.items.map((i) => i.serviceName).join(", ")]
            .filter(Boolean)
            .join(" · ")}
        />
      </main>
    </div>
  );
}
