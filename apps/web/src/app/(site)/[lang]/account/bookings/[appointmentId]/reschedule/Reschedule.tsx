"use client";

import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { createTranslator, type Language } from "@beavent/i18n";
import { calendarLanguage } from "@beavent/venue";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { DayStrip, TimeGrid, addDays, localDateOf, todayIn } from "@/components/booking/time";
import { rescheduleAppointment } from "@/lib/customer/actions";
import { findTimes, type ItemChoice } from "../../../../b/[business]/[location]/book/actions";

const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";
const quiet =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg px-2 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus";

export function Reschedule({
  language,
  appointment,
}: {
  language: Language;
  appointment: Schemas["AppointmentDetail"];
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const zone = appointment.timezone;
  const prefs = defaultDisplayPreferences(calendarLanguage(language), zone);
  const choices: ItemChoice[] = appointment.booking.items.map((i) => ({
    variantId: i.variantId,
    addonIds: i.addons.map((a) => a.id),
    staffId: i.staffRequested ? i.staffId : undefined,
  }));
  const key = JSON.stringify(choices);
  const firstDay = todayIn(zone);
  const [weekStart, setWeekStart] = useState(firstDay);
  const [pickedDay, setDay] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<{
    key: string;
    slots: Schemas["AvailabilitySlot"][];
  } | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const question = `${key}|${weekStart}|${attempt}`;
  const slots = loaded?.key === question ? loaded.slots : null;

  useEffect(() => {
    let cancelled = false;
    findTimes(
      appointment.booking.locationId,
      JSON.parse(key) as ItemChoice[],
      weekStart,
      addDays(weekStart, 6),
    )
      .then((r) => {
        if (!cancelled && r.ok) setLoaded({ key: question, slots: r.slots });
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [appointment.booking.locationId, key, weekStart, question]);

  // The current time is never offered as a "new" time.
  const offered = (slots ?? []).filter((s) => s.start !== appointment.start);
  const withTimes = new Set(offered.map((s) => localDateOf(s.start, zone)));
  const day = pickedDay ?? [...withTimes].sort()[0] ?? weekStart;
  const daySlots = offered.filter((s) => localDateOf(s.start, zone) === day);
  const current = `${formatDualDate(appointment.start, prefs, { weekday: true }).primary} · ${formatDualTime(appointment.start, prefs).primary}`;

  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-5 px-4 py-6 sm:px-6">
      <Link
        href={`/${language}/account/bookings/${appointment.id}`}
        className={`${quiet} self-start`}
      >
        ‹ {t("appointment.title")}
      </Link>
      <h1 className="bv-type-title1 text-fg">{t("appointment.reschedule")}</h1>
      <p className="bv-type-body text-fg-muted">{t("appointment.currently", { time: current })}</p>
      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
          {error}
        </p>
      ) : null}
      {chosen ? (
        <section
          role="dialog"
          aria-labelledby="move-title"
          className="flex flex-col gap-3 rounded-lg border border-border p-4"
        >
          <h2 id="move-title" className="bv-type-title2 text-fg">
            {t("appointment.moveTo", {
              time: `${formatDualDate(chosen, prefs, { weekday: true }).primary} · ${formatDualTime(chosen, prefs).primary}`,
            })}
          </h2>
          <p className="bv-type-body text-fg-muted">{t("appointment.pricesKept")}</p>
          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              className={primary}
              disabled={pending}
              onClick={() =>
                start(async () => {
                  const r = await rescheduleAppointment(language, appointment.id, chosen);
                  if (r.ok) router.push(`/${language}/account/bookings/${appointment.id}`);
                  else if (r.code === "SLOT_TAKEN") {
                    setError(t("booking.time.taken"));
                    setChosen(null);
                    setAttempt((n) => n + 1);
                  } else if (r.code === "PATCH_TEST_REQUIRED") setError(t("booking.patchTest"));
                  else setError(r.message || t("booking.error"));
                })
              }
            >
              {t("appointment.confirmMove")}
            </button>
            <button type="button" className={quiet} onClick={() => setChosen(null)}>
              {t("booking.back")}
            </button>
          </div>
        </section>
      ) : null}
      <div className="flex items-center justify-between gap-2">
        <button
          type="button"
          className={quiet}
          disabled={weekStart <= firstDay}
          onClick={() => {
            setWeekStart(addDays(weekStart, -7) < firstDay ? firstDay : addDays(weekStart, -7));
            setDay(null);
          }}
        >
          ‹ {t("booking.time.earlierWeek")}
        </button>
        <button
          type="button"
          className={quiet}
          onClick={() => {
            setWeekStart(addDays(weekStart, 7));
            setDay(null);
          }}
        >
          {t("booking.time.laterWeek")} ›
        </button>
      </div>
      <DayStrip
        from={weekStart}
        selected={day}
        language={language}
        withTimes={slots ? withTimes : null}
        onSelect={setDay}
      />
      {slots == null ? (
        <div aria-busy="true" className="grid grid-cols-3 gap-2 sm:grid-cols-4">
          {Array.from({ length: 8 }, (_, i) => (
            <div key={i} className="h-14 rounded-lg bg-surface motion-safe:animate-pulse" />
          ))}
        </div>
      ) : daySlots.length === 0 ? (
        <p className="bv-type-body text-fg-muted">
          {withTimes.size === 0 ? t("booking.time.noneThisWeek") : t("booking.time.none")}
        </p>
      ) : (
        <TimeGrid
          slots={daySlots}
          timeZone={zone}
          language={language}
          chosen={chosen}
          onChoose={setChosen}
        />
      )}
    </main>
  );
}
