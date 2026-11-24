import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { calendarLanguage, pick, waitlistWhen } from "@beavent/venue";
import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { PhoneSignIn } from "@/components/customer/PhoneSignIn";
import { SiteHeader } from "@/components/SiteHeader";
import { signOutCustomer } from "@/lib/customer/actions";
import { customerApi } from "@/lib/customer/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { menuNames } from "@/lib/venue";

export const metadata: Metadata = { robots: { index: false, follow: false } };

const UPCOMING = new Set(["REQUESTED", "PENDING_PAYMENT", "CONFIRMED", "ARRIVED", "IN_PROGRESS"]);

/** Upcoming (active and not yet over, soonest first) or past (everything else, newest first), as of now. */
function split(all: Schemas["MyAppointment"][], past: boolean): Schemas["MyAppointment"][] {
  const now = Date.now();
  return all
    .filter((a) => (UPCOMING.has(a.status) && new Date(a.end).getTime() > now) !== past)
    .sort((a, b) => (past ? b.start.localeCompare(a.start) : a.start.localeCompare(b.start)));
}

/** Bookings (C-4) on the web: upcoming and past appointments, grouped by day, each opening its detail (S-11). */
export default async function BookingsPage({
  params,
  searchParams,
}: PageProps<"/[lang]/account/bookings">) {
  await connection();
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  const past = (await searchParams).tab === "past";
  const api = await customerApi(language);
  const result = api ? await api.GET("/v1/me/appointments") : null;
  const signedIn = result?.data != null;

  const shown = split(result?.data?.items ?? [], past);
  const waitlists =
    signedIn && !past && api ? ((await api.GET("/v1/me/waitlist")).data?.items ?? []) : [];
  const names = await menuNames(
    shown.flatMap((a) => (a.place ? [a.place] : [])),
    language,
  );

  const days = new Map<string, Schemas["MyAppointment"][]>();
  for (const a of shown) {
    const prefs = defaultDisplayPreferences(calendarLanguage(language), a.timezone);
    const key = formatDualDate(a.start, prefs, { weekday: true }).primary;
    days.set(key, [...(days.get(key) ?? []), a]);
  }
  const tab = (active: boolean) =>
    `bv-type-label inline-flex min-h-12 flex-1 items-center justify-center rounded-lg ${
      active
        ? "bg-background text-fg shadow-none ring-1 ring-border-strong"
        : "text-fg-muted hover:text-fg"
    }`;

  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path="/account/bookings" />
      <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-6 sm:px-6">
        <h1 className="bv-type-title1 text-fg">{t("nav.bookings")}</h1>
        {!signedIn ? (
          <section className="flex flex-col gap-4">
            <p className="bv-type-title2 text-fg">{t("bookings.signedOut.title")}</p>
            <PhoneSignIn language={language} />
          </section>
        ) : (
          <>
            <nav className="flex gap-1 rounded-lg bg-surface p-1" aria-label={t("nav.bookings")}>
              <Link
                href={`/${language}/account/bookings`}
                aria-current={!past ? "page" : undefined}
                className={tab(!past)}
              >
                {t("account.bookings.upcoming")}
              </Link>
              <Link
                href={`/${language}/account/bookings?tab=past`}
                aria-current={past ? "page" : undefined}
                className={tab(past)}
              >
                {t("account.bookings.past")}
              </Link>
            </nav>
            {shown.length === 0 ? (
              <div className="flex flex-col gap-2">
                <p className="bv-type-title2 text-fg">
                  {past ? t("account.bookings.emptyPast") : t("account.bookings.emptyUpcoming")}
                </p>
                {!past ? (
                  <p className="bv-type-body text-fg-muted">{t("bookings.empty.body")}</p>
                ) : null}
              </div>
            ) : (
              [...days.entries()].map(([day, items]) => (
                <section key={day} className="flex flex-col gap-2">
                  <h2 className="bv-type-label text-fg-muted">{day}</h2>
                  <ul className="divide-y divide-border border-y border-border">
                    {items.map((a) => {
                      const prefs = defaultDisplayPreferences(
                        calendarLanguage(language),
                        a.timezone,
                      );
                      const time = formatDualTime(a.start, prefs);
                      return (
                        <li key={a.id}>
                          <Link
                            href={`/${language}/account/bookings/${a.id}`}
                            className="flex min-h-16 flex-col justify-center gap-1 py-3 focus-visible:outline-2 focus-visible:outline-focus"
                          >
                            <span className="bv-type-body text-fg">
                              {a.items
                                .map((i) => names.get(i.variantId)?.service ?? i.serviceName)
                                .join(", ")}
                            </span>
                            <span className="bv-type-caption text-fg-muted">
                              {[a.place?.businessName, `${time.primary} (${time.secondary})`]
                                .filter(Boolean)
                                .join(" · ")}
                            </span>
                            {a.status !== "CONFIRMED" ? (
                              <span className="bv-type-caption text-fg">
                                {t(`status.${a.status}`)}
                              </span>
                            ) : null}
                          </Link>
                        </li>
                      );
                    })}
                  </ul>
                </section>
              ))
            )}
            {waitlists.length > 0 ? (
              <section className="flex flex-col gap-2">
                <h2 className="bv-type-title2 text-fg">{t("waitlist.section")}</h2>
                <ul className="divide-y divide-border border-y border-border">
                  {waitlists.map((w) => (
                    <li key={w.id}>
                      <Link
                        href={`/${language}/account/waitlist/${w.id}`}
                        className="flex min-h-16 flex-col justify-center gap-1 py-3 focus-visible:outline-2 focus-visible:outline-focus"
                      >
                        <span className="bv-type-body text-fg">
                          {[pick(w.serviceName, language), w.place?.businessName]
                            .filter(Boolean)
                            .join(" · ")}
                        </span>
                        <span className="bv-type-caption text-fg-muted">
                          {waitlistWhen(w.days, w.parts, language, t)}
                        </span>
                        <span
                          className={`bv-type-caption ${w.offer ? "text-primary" : "text-fg-muted"}`}
                        >
                          {w.offer ? t("waitlist.offered") : t("waitlist.waiting")}
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
            <form action={signOutCustomer.bind(null, language)} className="pt-4">
              <button className="bv-type-label inline-flex min-h-12 items-center text-fg-muted underline underline-offset-4 hover:text-fg">
                {t("account.signOut")}
              </button>
            </form>
          </>
        )}
      </main>
    </div>
  );
}
