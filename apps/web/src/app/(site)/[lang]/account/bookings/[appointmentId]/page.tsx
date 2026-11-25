import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { formatDuration, localized } from "@beavent/i18n";
import { calendarLanguage, price } from "@beavent/venue";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { SiteHeader } from "@/components/SiteHeader";
import { customerApi } from "@/lib/customer/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { menuNames } from "@/lib/venue";
import { CancelBooking } from "./CancelBooking";

export const metadata: Metadata = { robots: { index: false, follow: false } };

const secondary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus";
const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus";

/** Appointment detail (S-11): status, when and where, what and with whom, the terms, and what can be done now. */
export default async function AppointmentPage({
  params,
}: PageProps<"/[lang]/account/bookings/[appointmentId]">) {
  await connection();
  const { lang, appointmentId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await customerApi(language);
  if (!api) redirect(`/${language}/account/bookings`);
  const { data: a, response } = await api.GET("/v1/me/appointments/{appointmentId}", {
    params: { path: { appointmentId } },
  });
  if (response.status === 401) redirect(`/${language}/account/bookings`);
  if (!a) notFound();

  const prefs = defaultDisplayPreferences(calendarLanguage(language), a.timezone);
  const date = formatDualDate(a.start, prefs, { weekday: true });
  const time = formatDualTime(a.start, prefs);
  const minutes = Math.round((new Date(a.end).getTime() - new Date(a.start).getTime()) / 60000);
  const business = a.place?.businessName ?? "";
  const venueHref = a.place
    ? `/${language}/b/${a.place.businessSlug}/${a.place.locationSlug}`
    : null;
  const heldUntil = a.payment?.expiresAt ? formatDualTime(a.payment.expiresAt, prefs).primary : "";
  const banner =
    a.status === "PENDING_PAYMENT" && a.payment?.state !== "EXPIRED"
      ? t("appointment.payBanner", { time: heldUntil })
      : a.status === "REQUESTED"
        ? t("appointment.requested", { name: business })
        : a.status === "DECLINED"
          ? t("appointment.declined", { name: business })
          : a.status === "EXPIRED"
            ? t("appointment.expired")
            : a.status === "CANCELLED"
              ? t("appointment.cancelled")
              : null;
  const names = await menuNames(a.place ? [a.place] : [], language);
  const finished = ["CANCELLED", "DECLINED", "EXPIRED", "COMPLETED", "NO_SHOW"].includes(a.status);
  const free = a.booking.policy.freeCancellationUntil;
  const forms = finished
    ? []
    : ((
        await api.GET("/v1/me/appointments/{appointmentId}/forms", {
          params: { path: { appointmentId } },
        })
      ).data?.items ?? []);

  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/account/bookings/${a.id}`} />
      <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-6 sm:px-6">
        <Link
          href={`/${language}/account/bookings`}
          className="bv-type-label inline-flex min-h-12 items-center self-start text-fg-muted hover:text-fg"
        >
          ‹ {t("nav.bookings")}
        </Link>
        <section className="flex flex-col gap-2">
          <p className="bv-type-label text-fg-muted">{t(`status.${a.status}`)}</p>
          <h1 className="bv-type-title1 text-fg">{date.primary}</h1>
          <p className="bv-type-body text-fg">
            {time.primary} · {formatDuration(minutes, t)}
          </p>
          <p className="bv-type-caption text-fg-muted">
            {date.secondary} · {time.secondary}
          </p>
          <p className="bv-type-caption text-fg-muted">
            {t("appointment.reference", { reference: a.reference })}
          </p>
        </section>
        {banner ? (
          <p
            role="status"
            className={`bv-type-body rounded-lg px-4 py-3 text-fg ${
              a.status === "REQUESTED" || a.status === "PENDING_PAYMENT"
                ? "bg-info-subtle"
                : "bg-surface"
            }`}
          >
            {banner}
          </p>
        ) : null}
        {a.status === "PENDING_PAYMENT" && a.payment && a.payment.state !== "EXPIRED" ? (
          <Link
            href={`/${language}/account/bookings/${a.id}/payment?result=pending`}
            className={`${primary} self-stretch sm:self-start`}
          >
            {t("payment.payNow", {
              amount: price(a.payment.dueNowMinor, a.payment.currency, language),
            })}
          </Link>
        ) : null}

        {forms.length > 0 ? (
          <section className="flex flex-col gap-3">
            <h2 className="bv-type-title2 text-fg">{t("appointment.forms.title")}</h2>
            <ul className="divide-y divide-border border-y border-border">
              {forms.map((f) => (
                <li key={f.formId}>
                  <Link
                    href={`/${language}/account/bookings/${a.id}/forms/${f.formId}`}
                    className="flex min-h-14 items-center justify-between gap-4 py-3 hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus"
                  >
                    <span className="flex flex-col">
                      <span className="bv-type-body text-fg">{localized(f.title, language)}</span>
                      {!f.required ? (
                        <span className="bv-type-caption text-fg-muted">
                          {t("appointment.forms.optional")}
                        </span>
                      ) : null}
                    </span>
                    <span
                      className={`bv-type-label shrink-0 ${f.done ? "text-fg-muted" : "text-primary"}`}
                    >
                      {f.done ? t("appointment.forms.done") : t("appointment.forms.todo")} ›
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {a.place ? (
          <section className="flex flex-col gap-3">
            <Link
              href={venueHref!}
              className="flex min-h-16 flex-col justify-center border-y border-border py-3 focus-visible:outline-2 focus-visible:outline-focus"
            >
              <span className="bv-type-body-strong text-fg">{business}</span>
              {a.place.landmark ? (
                <span className="bv-type-caption text-fg-muted">{a.place.landmark}</span>
              ) : null}
            </Link>
            {a.place.phone && !finished ? (
              <a href={`tel:${a.place.phone}`} className={`${secondary} self-start`}>
                {t("venue.call")}
              </a>
            ) : null}
          </section>
        ) : null}

        <section className="flex flex-col gap-2">
          <ul className="divide-y divide-border border-y border-border">
            {a.booking.items.map((i) => (
              <li key={i.variantId + i.start} className="flex justify-between gap-4 py-3">
                <span className="flex flex-col">
                  <span className="bv-type-body text-fg">
                    {names.get(i.variantId)?.service ?? i.serviceName}
                    {(names.get(i.variantId)?.variant ?? i.variantName)
                      ? ` · ${names.get(i.variantId)?.variant ?? i.variantName}`
                      : ""}
                  </span>
                  <span className="bv-type-caption text-fg-muted">
                    {t("booking.review.with", { name: i.staffName })}
                    {i.addons.length > 0 ? ` · ${i.addons.map((x) => x.name).join(", ")}` : ""}
                  </span>
                </span>
                <span className="bv-type-body text-fg">
                  {price(
                    i.priceMinor + i.addons.reduce((s, x) => s + x.priceMinor, 0),
                    i.currency,
                    language,
                  )}
                </span>
              </li>
            ))}
          </ul>
          <div className="flex justify-between">
            <span className="bv-type-body-strong text-fg">{t("booking.review.total")}</span>
            <span className="bv-type-body-strong text-fg">
              {price(a.booking.totalMinor, a.booking.currency, language)}
            </span>
          </div>
          {a.payment && a.payment.state !== "NOT_REQUIRED" && !finished ? (
            <>
              <div className="flex justify-between">
                <span className="bv-type-body text-fg">
                  {a.payment.state === "PAID" ? t("appointment.depositPaid") : t("payment.dueNow")}
                </span>
                <span className="bv-type-body text-fg">
                  {price(a.payment.dueNowMinor, a.payment.currency, language)}
                </span>
              </div>
              {a.payment.atVenueMinor > 0 ? (
                <div className="flex justify-between">
                  <span className="bv-type-body text-fg-muted">
                    {t("appointment.payAtVenueLeft")}
                  </span>
                  <span className="bv-type-body text-fg-muted">
                    {price(a.payment.atVenueMinor, a.payment.currency, language)}
                  </span>
                </div>
              ) : null}
            </>
          ) : null}
        </section>

        {!finished && a.booking.policy.cancellationWindowMinutes > 0 ? (
          <p className="bv-type-label text-fg">
            {t("booking.review.freeUntil", {
              time: `${formatDualDate(free, prefs, { weekday: true }).primary}, ${formatDualTime(free, prefs).primary}`,
            })}
          </p>
        ) : null}

        <section className="flex flex-col gap-3">
          {a.canReschedule ? (
            <Link href={`/${language}/account/bookings/${a.id}/reschedule`} className={secondary}>
              {t("appointment.reschedule")}
            </Link>
          ) : a.cancellation.allowed && a.status === "CONFIRMED" ? (
            <p className="bv-type-body text-fg-muted">{t("appointment.callToChange")}</p>
          ) : null}
          {a.cancellation.allowed ? (
            <CancelBooking
              language={language}
              appointmentId={a.id}
              request={a.status === "REQUESTED"}
              feeText={
                a.cancellation.free
                  ? t("appointment.cancelFree")
                  : t("appointment.cancelFee", {
                      amount: price(
                        a.cancellation.feeMinor,
                        a.cancellation.currency ?? "ETB",
                        language,
                      ),
                    })
              }
            />
          ) : null}
          {finished && venueHref ? (
            <Link href={`${venueHref}/book`} className={primary}>
              {a.status === "DECLINED" ? t("appointment.findAnother") : t("appointment.bookAgain")}
            </Link>
          ) : null}
        </section>
      </main>
    </div>
  );
}
