import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { SiteHeader } from "@/components/SiteHeader";
import { customerApi } from "@/lib/customer/session";
import { routeLanguage } from "@/lib/i18n";
import { Reschedule } from "./Reschedule";

export const metadata: Metadata = { robots: { index: false, follow: false } };

/** Reschedule (S-12): the time step for the same services and chosen professional. */
export default async function ReschedulePage({
  params,
}: PageProps<"/[lang]/account/bookings/[appointmentId]/reschedule">) {
  await connection();
  const { lang, appointmentId } = await params;
  const language = routeLanguage(lang);
  const api = await customerApi(language);
  if (!api) redirect(`/${language}/account/bookings`);
  const { data } = await api.GET("/v1/me/appointments/{appointmentId}", {
    params: { path: { appointmentId } },
  });
  if (!data) notFound();
  if (!data.canReschedule) redirect(`/${language}/account/bookings/${appointmentId}`);
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/account/bookings/${appointmentId}/reschedule`} />
      <Reschedule language={language} appointment={data} />
    </div>
  );
}
