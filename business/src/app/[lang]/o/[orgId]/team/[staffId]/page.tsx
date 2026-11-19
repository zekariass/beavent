import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/session";
import { HoursForm } from "./HoursForm";

/** The next Monday (or today if it is Monday), in Addis Ababa. */
function nextMonday(): string {
  const now = new Date(Date.now() + 3 * 3600_000);
  const offset = (8 - (now.getUTCDay() || 7)) % 7;
  return new Date(now.getTime() + offset * 86_400_000).toISOString().slice(0, 10);
}

export default async function StaffPage({ params }: PageProps<"/[lang]/o/[orgId]/team/[staffId]">) {
  const { lang, orgId, staffId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const staff = unwrap(
    await api.GET("/v1/orgs/{orgId}/staff/{staffId}", { params: { path: { orgId, staffId } } }),
  );
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", {
      params: { path: { orgId }, query: { limit: 100 } },
    }),
  );
  const pattern = unwrap(
    await api.GET("/v1/orgs/{orgId}/staff/{staffId}/working-pattern", {
      params: { path: { orgId, staffId }, query: { date: nextMonday() } },
    }),
  );
  const days = [1, 2, 3, 4, 5, 6, 7].map((day) => ({
    day,
    label: t(`portal.weekday.${day}` as "portal.weekday.1"),
    intervals: pattern.entries
      .filter((e) => e.isoWeekday === day && (e.week ?? 1) === 1)
      .map((e) => ({ startsAt: e.startsAt, endsAt: e.endsAt })),
  }));
  return (
    <>
      <h1 className="text-2xl font-semibold">{staff.displayName}</h1>
      <h2 className="font-semibold">{t("portal.hours.title")}</h2>
      <HoursForm
        language={language}
        orgId={orgId}
        staffId={staffId}
        effectiveFrom={nextMonday()}
        locations={locations.items
          .filter((l) => staff.locationIds.includes(l.id))
          .map((l) => ({ id: l.id, label: l.name }))}
        days={days}
        labels={{
          from: t("portal.hours.from"),
          dayOff: t("portal.hours.dayOff"),
          save: t("portal.hours.save"),
          location: t("portal.service.locations"),
        }}
      />
    </>
  );
}
