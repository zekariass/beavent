import { formatDualDate } from "@beavent/calendar";
import { addDays, calendarLanguage, pick, todayIn } from "@beavent/venue";
import { defaultDisplayPreferences } from "@beavent/calendar";
import Link from "next/link";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { CalendarBoard } from "./CalendarBoard";

/**
 * Calendar (P-12): one day at a location, a column per team member (Fresha's day view). The board is interactive:
 * appointments open a panel with their details and the next steps (arrived, start, complete, no-show; requests are
 * accepted or declined).
 */
export default async function CalendarPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/calendar">) {
  const { lang, orgId } = await params;
  const query = (await searchParams) as { date?: string; location?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId } } }),
  ).items.filter((l) => l.status === "ACTIVE");
  const location = locations.find((l) => l.id === query.location) ?? locations[0];
  if (!location) {
    return <p className="bv-type-body text-fg-muted">{t("portal.calendar.noTeam")}</p>;
  }
  const zone = location.timezone;
  const today = todayIn(zone);
  const date = query.date && /^\d{4}-\d{2}-\d{2}$/.test(query.date) ? query.date : today;
  const calendar = unwrap(
    await api.GET("/v1/orgs/{orgId}/calendar", {
      params: { path: { orgId }, query: { locationId: location.id, from: date } },
    }),
  );
  // What the desk can book here: every active service variant at this location and who does it.
  const menu = unwrap(
    await api.GET("/v1/orgs/{orgId}/services", {
      params: { path: { orgId }, query: { locationId: location.id, status: "ACTIVE", limit: 100 } },
    }),
  );
  const services = menu.items.flatMap((s) =>
    s.variants.map((v) => ({
      variantId: v.id!,
      label: [pick(s.name, language), v.name ? pick(v.name, language) : ""]
        .filter(Boolean)
        .join(" · "),
      durationMinutes: v.durationMinutes,
      staffIds: s.staffIds,
    })),
  );
  const prefs = defaultDisplayPreferences(calendarLanguage(language), zone);
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  const title = formatDualDate({ year: y, month: m, day: d }, prefs, { weekday: true });
  const href = (to: string) =>
    `/${language}/business/o/${orgId}/calendar?date=${to}${locations.length > 1 ? `&location=${location.id}` : ""}`;
  const step =
    "bv-type-label inline-flex min-h-10 items-center rounded-lg border border-border-strong px-3 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus";

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-col">
          <h1 className="bv-type-title1">{title.primary}</h1>
          <p className="bv-type-caption text-fg-muted">{title.secondary}</p>
        </div>
        <nav className="flex flex-wrap items-center gap-2" aria-label={t("portal.nav.calendar")}>
          <Link
            href={href(addDays(date, -1))}
            className={step}
            aria-label={t("portal.calendar.previous")}
          >
            ‹
          </Link>
          <Link
            href={href(today)}
            className={step}
            aria-current={date === today ? "date" : undefined}
          >
            {t("portal.calendar.today")}
          </Link>
          <Link
            href={href(addDays(date, 1))}
            className={step}
            aria-label={t("portal.calendar.next")}
          >
            ›
          </Link>
          <Link
            href={`/${language}/business/o/${orgId}/calendar/waitlist?location=${location.id}`}
            className={step}
          >
            {t("portal.calendar.waitlist")}
          </Link>
          {locations.length > 1
            ? locations.map((l) => (
                <Link
                  key={l.id}
                  href={`/${language}/business/o/${orgId}/calendar?date=${date}&location=${l.id}`}
                  aria-current={l.id === location.id ? "page" : undefined}
                  className={`${step} ${l.id === location.id ? "border-primary bg-primary-subtle" : ""}`}
                >
                  {l.name}
                </Link>
              ))
            : null}
        </nav>
      </div>
      {calendar.staff.length === 0 ? (
        <p className="bv-type-body text-fg-muted">{t("portal.calendar.noTeam")}</p>
      ) : (
        <CalendarBoard
          language={language}
          orgId={orgId}
          calendar={calendar}
          date={date}
          isToday={date === today}
          services={services}
        />
      )}
    </>
  );
}
