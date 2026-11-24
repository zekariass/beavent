import Link from "next/link";
import {
  formatEthiopianDate,
  formatEthiopianTime,
  gregorianToEthiopian,
  toEthiopianTime,
  zoned,
} from "@beavent/calendar";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";

const ZONE = "Africa/Addis_Ababa";
const pad = (n: number) => String(n).padStart(2, "0");

function monday(of: string | undefined): Date {
  const base =
    of && /^\d{4}-\d{2}-\d{2}$/.test(of)
      ? new Date(`${of}T00:00:00Z`)
      : new Date(Date.now() + 3 * 3600_000);
  const day = base.getUTCDay() || 7;
  return new Date(
    Date.UTC(base.getUTCFullYear(), base.getUTCMonth(), base.getUTCDate() - (day - 1)),
  );
}

const iso = (d: Date) => d.toISOString().slice(0, 10);

export default async function RotaPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/rota">) {
  const { lang, orgId } = await params;
  const { week } = (await searchParams) as { week?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const start = monday(week);
  const dates = Array.from({ length: 7 }, (_, i) => new Date(start.getTime() + i * 86_400_000));
  const schedule = unwrap(
    await api.GET("/v1/orgs/{orgId}/schedule", {
      params: { path: { orgId }, query: { from: iso(dates[0]!), to: iso(dates[6]!) } },
    }),
  );
  const staff = unwrap(await api.GET("/v1/orgs/{orgId}/staff", { params: { path: { orgId } } }));
  // Amharic readers see Ethiopian dates and Ethiopian time; everyone else the international clock.
  const ethiopian = language === "am";
  const clock = (instant: string) => {
    const local = zoned(instant, ZONE);
    return ethiopian
      ? formatEthiopianTime(toEthiopianTime(local.hour, local.minute), "am")
      : `${pad(local.hour)}:${pad(local.minute)}`;
  };
  const dayLabel = (d: Date, i: number) => {
    const label = t(`portal.weekday.${i + 1}` as "portal.weekday.1");
    const date = { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
    return ethiopian
      ? `${label} ${formatEthiopianDate(gregorianToEthiopian(date), "am").split(" ").slice(0, 2).join(" ")}`
      : `${label} ${date.day}/${date.month}`;
  };
  const base = `/${language}/business/o/${orgId}/rota`;
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="bv-type-title1">{t("portal.rota.title")}</h1>
        <div className="flex gap-3 text-sm">
          <Link
            href={`${base}?week=${iso(new Date(start.getTime() - 7 * 86_400_000))}`}
            className="underline"
          >
            {t("portal.rota.previous")}
          </Link>
          <Link
            href={`${base}?week=${iso(new Date(start.getTime() + 7 * 86_400_000))}`}
            className="underline"
          >
            {t("portal.rota.next")}
          </Link>
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr>
              <th />
              {dates.map((d, i) => (
                <th key={i} className="px-2 py-1 text-left font-medium">
                  {dayLabel(d, i)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {staff.items.map((s) => (
              <tr key={s.id} className="border-t border-border align-top">
                <td className="py-2 pr-3 font-medium">{s.displayName}</td>
                {dates.map((d) => {
                  const day = schedule.days.find((x) => x.staffId === s.id && x.date === iso(d));
                  return (
                    <td key={iso(d)} className="px-2 py-2">
                      {day && day.intervals.length > 0 ? (
                        day.intervals.map((i) => (
                          <div key={i.startsAt}>
                            {clock(i.startsAt)}–{clock(i.endsAt)}
                          </div>
                        ))
                      ) : (
                        <span className="text-fg-muted">{t("portal.rota.off")}</span>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
