import {
  calendarLanguage,
  directionsUrl,
  dualClock,
  openStatus,
  pick,
  policySentences,
  weekHours,
  type Venue,
} from "@beavent/venue";
import { translator } from "@/lib/i18n";
import { WEEKDAYS } from "@beavent/calendar";
import type { Language } from "@beavent/i18n";

/** About (S-04): description, opening hours with today in bold, upcoming closures, the policies in plain sentences. */
export function About({ venue, language, now }: { venue: Venue; language: Language; now: Date }) {
  const t = translator(language);
  const description = pick(venue.description, language);
  const status = openStatus(venue, now);
  const week = weekHours(venue);
  const names = WEEKDAYS[calendarLanguage(language)];
  const sentences = policySentences(venue, language, t);
  const directions = directionsUrl(venue);

  return (
    <div className="flex flex-col gap-8">
      {description ? <p className="bv-type-body max-w-prose text-fg">{description}</p> : null}

      <div className="flex flex-col gap-2">
        <h3 className="bv-type-body-strong text-fg">{t("venue.hours")}</h3>
        <ul className="divide-y divide-border border-y border-border">
          {week.map((day) => {
            const isToday = day.isoWeekday === status.today;
            return (
              <li
                key={day.isoWeekday}
                className={`flex items-start justify-between gap-4 py-3 ${isToday ? "bv-type-body-strong text-fg" : "bv-type-body text-fg-muted"}`}
              >
                <span>{names[day.isoWeekday - 1]}</span>
                <span className="flex flex-col items-end gap-1 text-end">
                  {day.ranges.length === 0 ? (
                    <span>{t("venue.closed")}</span>
                  ) : (
                    day.ranges.map((r) => {
                      const a = dualClock(r.opens, language);
                      const b = dualClock(r.closes, language);
                      return (
                        <span key={r.opens} className="flex flex-col items-end">
                          <span>
                            {a.primary} – {b.primary}
                          </span>
                          <span className="bv-type-caption text-fg-subtle">
                            {a.secondary} – {b.secondary}
                          </span>
                        </span>
                      );
                    })
                  )}
                </span>
              </li>
            );
          })}
        </ul>
        {venue.upcomingClosures.length > 0 ? (
          <div className="flex flex-col gap-1 pt-2">
            <p className="bv-type-label text-fg-muted">{t("venue.closures")}</p>
            <ul className="bv-type-body flex flex-col gap-1 text-fg">
              {venue.upcomingClosures.map((c, i) => (
                <li key={i}>
                  {[
                    c.startsAt
                      ? new Intl.DateTimeFormat(language, {
                          dateStyle: "medium",
                          timeZone: venue.timezone,
                        }).format(new Date(c.startsAt))
                      : "",
                    c.reason ?? "",
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>

      {directions || venue.addressLine ? (
        <div className="flex flex-col gap-1">
          {venue.addressLine ? <p className="bv-type-body text-fg">{venue.addressLine}</p> : null}
          {directions ? (
            <a
              href={directions}
              rel="noopener noreferrer"
              target="_blank"
              className="bv-type-label inline-flex min-h-12 items-center text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-focus"
            >
              {t("venue.directions")}
            </a>
          ) : null}
        </div>
      ) : null}

      {sentences.length > 0 ? (
        <div className="flex flex-col gap-2">
          <h3 className="bv-type-body-strong text-fg">{t("venue.policies")}</h3>
          <ul className="bv-type-body flex flex-col gap-2 text-fg">
            {sentences.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
