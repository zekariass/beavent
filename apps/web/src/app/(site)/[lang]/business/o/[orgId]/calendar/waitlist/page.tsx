import {
  defaultDisplayPreferences,
  formatDualDate,
  formatStandardTime,
  zoned,
} from "@beavent/calendar";
import { calendarLanguage, pick, waitlistWhen } from "@beavent/venue";
import Link from "next/link";
import { listClass } from "@/components/business/ui";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";

/** Who is waiting for a time at a location, oldest first (S-15, the business's side). */
export default async function WaitlistPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/calendar/waitlist">) {
  const { lang, orgId } = await params;
  const query = (await searchParams) as { location?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId } } }),
  ).items.filter((l) => l.status === "ACTIVE");
  const location = locations.find((l) => l.id === query.location) ?? locations[0];
  const entries = location
    ? unwrap(
        await api.GET("/v1/orgs/{orgId}/waitlist", {
          params: { path: { orgId }, query: { locationId: location.id } },
        }),
      ).items
    : [];
  const base = `/${language}/business/o/${orgId}`;
  return (
    <>
      <div className="flex flex-col gap-1">
        <Link href={`${base}/calendar`} className="bv-type-label text-fg-muted hover:text-fg">
          ‹ {t("portal.nav.calendar")}
        </Link>
        <h1 className="bv-type-title1">{t("portal.waitlist.title")}</h1>
        <p className="bv-type-body text-fg-muted">{t("portal.waitlist.intro")}</p>
      </div>
      {entries.length === 0 ? (
        <p className="bv-type-body text-fg-muted">{t("portal.waitlist.empty")}</p>
      ) : (
        <ul className={listClass}>
          {entries.map((w) => {
            const offer = w.offer;
            const at = offer ? zoned(offer.start, w.timezone) : null;
            return (
              <li key={w.id} className="flex flex-col gap-1 px-3 py-3">
                <span className="bv-type-body text-fg">
                  {[w.customerName, pick(w.serviceName, language)].filter(Boolean).join(" · ")}
                </span>
                <span className="bv-type-caption text-fg-muted">
                  {[waitlistWhen(w.days, w.parts, language, t), w.staffName]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
                {offer && at ? (
                  <span className="bv-type-caption text-fg">
                    {t("portal.waitlist.offered", {
                      time: `${
                        formatDualDate(
                          offer.start,
                          defaultDisplayPreferences(calendarLanguage(language), w.timezone),
                          { weekday: true },
                        ).primary
                      } · ${formatStandardTime(at.hour, at.minute)}`,
                    })}
                  </span>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}
