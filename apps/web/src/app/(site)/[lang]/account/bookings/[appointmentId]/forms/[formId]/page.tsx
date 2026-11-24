import { localized } from "@beavent/i18n";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { SiteHeader } from "@/components/SiteHeader";
import { customerApi } from "@/lib/customer/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { FormFill } from "./FormFill";

export const metadata: Metadata = { robots: { index: false, follow: false } };

/** A form to fill in before a visit (S-09 forms, after booking): the business's questions, then a typed signature. */
export default async function AppointmentFormPage({
  params,
}: PageProps<"/[lang]/account/bookings/[appointmentId]/forms/[formId]">) {
  await connection();
  const { lang, appointmentId, formId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await customerApi(language);
  if (!api) redirect(`/${language}/account/bookings`);
  const [questions, appointment] = await Promise.all([
    api.GET("/v1/me/appointments/{appointmentId}/forms/{formId}", {
      params: { path: { appointmentId, formId } },
    }),
    api.GET("/v1/me/appointments/{appointmentId}", { params: { path: { appointmentId } } }),
  ]);
  if (questions.response.status === 401) redirect(`/${language}/account/bookings`);
  if (!questions.data || !appointment.data) notFound();
  const form = questions.data;
  const back = `/${language}/account/bookings/${appointmentId}`;
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/account/bookings/${appointmentId}/forms/${formId}`} />
      <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-6 sm:px-6">
        <Link
          href={back}
          className="bv-type-label inline-flex min-h-12 items-center self-start text-fg-muted hover:text-fg"
        >
          ‹ {t("form.back")}
        </Link>
        <div className="flex flex-col gap-2">
          <p className="bv-type-label text-fg-muted">{appointment.data.place?.businessName}</p>
          <h1 className="bv-type-title1 text-fg">{localized(form.title, language)}</h1>
          {form.intro && Object.keys(form.intro).length > 0 ? (
            <p className="bv-type-body text-fg">{localized(form.intro, language)}</p>
          ) : null}
          {form.done ? <p className="bv-type-body text-fg-muted">{t("form.again")}</p> : null}
        </div>
        <FormFill
          language={language}
          appointmentId={appointmentId}
          form={form}
          business={appointment.data.place?.businessName ?? ""}
          back={back}
        />
      </main>
    </div>
  );
}
