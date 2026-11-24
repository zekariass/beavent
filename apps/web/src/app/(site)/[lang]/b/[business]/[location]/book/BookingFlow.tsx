"use client";

import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { createTranslator, formatDuration, type Language } from "@beavent/i18n";
import {
  calendarLanguage,
  pick,
  price,
  priceText,
  type Venue,
  type VenueService,
} from "@beavent/venue";
import Link from "next/link";
import { useEffect, useMemo, useState, useSyncExternalStore, useTransition } from "react";
import { DayStrip, TimeGrid, addDays, localDateOf, todayIn } from "@/components/booking/time";
import { book, findTimes, holdTime, releaseHold, sendCode, type ItemChoice } from "./actions";

interface Picked {
  serviceId: string;
  variantId: string;
  addonIds: string[];
}

type Step = "services" | "pro" | "time" | "review" | "done";

const primaryButton =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";
const secondaryButton =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg border border-border-strong px-4 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus";
const input =
  "bv-type-body min-h-12 w-full rounded-lg border border-border-strong bg-background px-3 text-fg focus-visible:outline-2 focus-visible:outline-focus";

/**
 * The booking flow (S-06 → S-10b, SERVICES_MARKETPLACE_DESIGN.md §5): services, professional (skipped when only one
 * person can do them), time, review with inline sign-in, confirmation. A summary column on desktop, a bar on phones.
 */
export function BookingFlow({
  venue,
  language,
  signedInAs,
  preselect,
}: {
  venue: Venue;
  language: Language;
  signedInAs: string | null;
  preselect?: { serviceId?: string; staffId?: string };
}) {
  const t = useMemo(() => createTranslator(language), [language]);
  const prefs = useMemo(
    () => defaultDisplayPreferences(calendarLanguage(language), venue.timezone),
    [language, venue.timezone],
  );
  const services = useMemo(() => venue.groups.flatMap((g) => g.services), [venue]);
  const byId = useMemo(() => new Map(services.map((s) => [s.id, s])), [services]);
  // Booked items carry names in the business's language; show the reader's from the menu when we have them.
  const itemName = (variantId: string, fallback: string, variantFallback?: string | null) => {
    const service = services.find((s) => s.variants.some((v) => v.id === variantId));
    const variant = service?.variants.find((v) => v.id === variantId);
    const name = service ? pick(service.name, language) : fallback;
    const option = variant ? pick(variant.name, language) : (variantFallback ?? "");
    return option ? `${name} · ${option}` : name;
  };

  const [picked, setPicked] = useState<Picked[]>(() => {
    const s = preselect?.serviceId ? byId.get(preselect.serviceId) : undefined;
    return s ? [{ serviceId: s.id, variantId: s.variants[0]!.id, addonIds: [] }] : [];
  });
  const [staffId, setStaffId] = useState<string | null>(preselect?.staffId ?? null);
  const [step, setStep] = useState<Step>("services");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  // Professionals who do every chosen service.
  const pros = venue.team.filter((p) => picked.every((x) => p.serviceIds.includes(x.serviceId)));
  const steps: Step[] =
    pros.length > 1 ? ["services", "pro", "time", "review"] : ["services", "time", "review"];
  const index = steps.indexOf(step);

  const choices: ItemChoice[] = picked.map((p) => ({
    variantId: p.variantId,
    addonIds: p.addonIds,
    staffId: staffId && pros.some((x) => x.id === staffId) ? staffId : undefined,
  }));

  // Totals: variant prices (lowest when they vary by professional) plus add-ons; durations likewise.
  const lines = picked.map((p) => {
    const service = byId.get(p.serviceId)!;
    const variant = service.variants.find((v) => v.id === p.variantId)!;
    const addons = service.addOns.filter((a) => p.addonIds.includes(a.id));
    return { service, variant, addons };
  });
  const currency = lines[0]?.variant.currency ?? "ETB";
  const totalMin = lines.reduce(
    (sum, l) => sum + l.variant.priceMinMinor + l.addons.reduce((s, a) => s + a.priceMinor, 0),
    0,
  );
  const varies = lines.some(
    (l) => l.variant.priceMinMinor !== l.variant.priceMaxMinor || l.variant.priceType === "FROM",
  );
  const minutes = lines.reduce(
    (sum, l) =>
      sum + l.variant.durationMinutes + l.addons.reduce((s, a) => s + a.durationMinutes, 0),
    0,
  );

  // --- time ------------------------------------------------------------------------------------------------
  const firstDay = todayIn(venue.timezone);
  const [weekStart, setWeekStart] = useState(firstDay);
  const [refresh, setRefresh] = useState(0);
  // null = automatic: the first day of the week that has times (Fresha opens there); a tap fixes the day.
  const [pickedDay, setDay] = useState<string | null>(null);
  // Times are kept with the question they answer, so a new question shows the skeleton until its answer arrives.
  const [loaded, setLoaded] = useState<{
    key: string;
    slots: Schemas["AvailabilitySlot"][];
  } | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);
  const choiceKey = JSON.stringify(choices);
  const question = `${choiceKey}|${weekStart}|${refresh}`;
  const slots = loaded?.key === question ? loaded.slots : null;
  // The time-zone note appears only when the device's zone differs (known only in the browser).
  const otherZone = useSyncExternalStore(
    () => () => {},
    () => Intl.DateTimeFormat().resolvedOptions().timeZone !== venue.timezone,
    () => false,
  );

  useEffect(() => {
    if (step !== "time") return;
    let cancelled = false;
    findTimes(
      venue.locationId,
      JSON.parse(choiceKey) as ItemChoice[],
      weekStart,
      addDays(weekStart, 6),
    )
      .then((r) => {
        if (cancelled) return;
        if (r.ok) setLoaded({ key: question, slots: r.slots });
        else setError(t("booking.error"));
      })
      .catch(() => !cancelled && setError(t("booking.error")));
    return () => {
      cancelled = true;
    };
  }, [step, weekStart, choiceKey, question, venue.locationId, t]);

  const daysWithTimes = new Set((slots ?? []).map((s) => localDateOf(s.start, venue.timezone)));
  const day = pickedDay ?? [...daysWithTimes].sort()[0] ?? weekStart;
  const daySlots = (slots ?? []).filter((s) => localDateOf(s.start, venue.timezone) === day);
  const nextDay = [...daysWithTimes].sort().find((d) => d > day);

  // --- review ----------------------------------------------------------------------------------------------
  const [hold, setHold] = useState<Schemas["Hold"] | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [note, setNote] = useState("");
  const [phone, setPhone] = useState("");
  const [challenge, setChallenge] = useState<{ id: string; phone: string } | null>(null);
  const [code, setCode] = useState("");
  // C-6 step 3: someone new says what to call them before the booking goes through.
  const [askName, setAskName] = useState(false);
  const [givenName, setGivenName] = useState("");
  const [fatherName, setFatherName] = useState("");
  const [done, setDone] = useState<Schemas["Appointment"] | null>(null);

  useEffect(() => {
    if (step !== "review") return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [step]);
  const expired = hold != null && new Date(hold.expiresAt).getTime() <= now;

  const go = (next: Step) => {
    setError(null);
    setStep(next);
    window.scrollTo({ top: 0 });
  };

  const back = () => {
    if (step === "review" && hold) {
      void releaseHold(venue.locationId, hold.holdId, hold.holderKey);
      setHold(null);
    }
    const previous = steps[index - 1];
    if (previous) go(previous);
  };

  const holdChosen = () =>
    start(async () => {
      if (!chosen) return;
      const r = await holdTime(venue.locationId, choices, chosen);
      if (r.ok) {
        setHold(r.hold);
        go("review");
      } else if (r.code === "SLOT_TAKEN") {
        setError(t("booking.time.taken"));
        setChosen(null);
        setRefresh((n) => n + 1);
      } else {
        setError(r.message || t("booking.error"));
      }
    });

  const confirm = () =>
    start(async () => {
      if (!hold) return;
      const r = await book(language, {
        locationId: venue.locationId,
        holdId: hold.holdId,
        holderKey: hold.holderKey,
        note,
        challengeId: askName ? undefined : challenge?.id,
        code: challenge && !askName ? code : undefined,
        ...(askName ? { givenName, fatherName } : {}),
      });
      if (r.ok) {
        setDone(r.appointment);
        go("done");
      } else if (r.code === "NAME_NEEDED") {
        setAskName(true);
      } else if (r.code === "HOLD_EXPIRED") {
        setHold(null);
        setChosen(null);
        go("time");
        setError(t("booking.review.expired"));
      } else if (r.code === "PATCH_TEST_REQUIRED") {
        setError(t("booking.patchTest"));
      } else {
        setError(r.message || t("booking.error"));
      }
    });

  const askForCode = () =>
    start(async () => {
      const r = await sendCode(language, phone);
      if (r.ok) setChallenge({ id: r.challengeId, phone: r.phone });
      else setError(r.message || t("booking.error"));
    });

  const requiresApproval = hold?.booking.policy.requiresApproval ?? venue.policy.requiresApproval;
  const venueHref = `/${language}/b/${venue.businessSlug}/${venue.locationSlug}`;

  // --- pieces ------------------------------------------------------------------------------------------------
  const timeText = (iso: string) => formatDualTime(iso, prefs);
  const dateText = (iso: string) => formatDualDate(iso, prefs, { weekday: true });

  const summary = (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col">
        <p className="bv-type-body-strong text-fg">{venue.businessName}</p>
        {venue.landmark ? <p className="bv-type-caption text-fg-muted">{venue.landmark}</p> : null}
      </div>
      {lines.length > 0 ? (
        <ul className="flex flex-col gap-3">
          {lines.map((l) => (
            <li key={l.variant.id} className="flex items-start justify-between gap-3">
              <span className="flex flex-col">
                <span className="bv-type-body text-fg">{pick(l.service.name, language)}</span>
                <span className="bv-type-caption text-fg-muted">
                  {[
                    pick(l.variant.name, language),
                    formatDuration(
                      l.variant.durationMinutes +
                        l.addons.reduce((s, a) => s + a.durationMinutes, 0),
                      t,
                    ),
                    ...l.addons.map((a) => pick(a.name, language)),
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              </span>
              <span className="bv-type-body text-fg">{priceText([l.variant], language, t)}</span>
            </li>
          ))}
        </ul>
      ) : null}
      {hold ? (
        <div className="flex flex-col">
          <span className="bv-type-body-strong text-fg">
            {dateText(hold.booking.start).primary} · {timeText(hold.booking.start).primary}
          </span>
          <span className="bv-type-caption text-fg-muted">
            {dateText(hold.booking.start).secondary} · {timeText(hold.booking.start).secondary}
          </span>
        </div>
      ) : null}
      {lines.length > 0 ? (
        <div className="flex items-center justify-between border-t border-border pt-3">
          <span className="bv-type-body-strong text-fg">{t("booking.review.total")}</span>
          <span className="bv-type-body-strong text-fg">
            {hold
              ? price(hold.booking.totalMinor, hold.booking.currency, language)
              : varies
                ? t("venue.from", { price: price(totalMin, currency, language) })
                : price(totalMin, currency, language)}
          </span>
        </div>
      ) : null}
    </div>
  );

  const continueAction =
    step === "services" ? (
      <button
        type="button"
        className={primaryButton}
        disabled={picked.length === 0}
        onClick={() => go(steps[index + 1]!)}
      >
        {t("booking.continue")}
      </button>
    ) : step === "pro" ? (
      <button type="button" className={primaryButton} onClick={() => go("time")}>
        {t("booking.continue")}
      </button>
    ) : step === "time" ? (
      <button
        type="button"
        className={primaryButton}
        disabled={!chosen || pending}
        onClick={holdChosen}
      >
        {t("booking.continue")}
      </button>
    ) : null;

  if (step === "done" && done) {
    const requested = done.status === "REQUESTED";
    return (
      <main className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-6 px-4 py-12 sm:px-6">
        <h1 className="bv-type-title1 text-fg" role="status">
          {requested ? t("booking.done.requested") : t("booking.done.booked")}
        </h1>
        <p className="bv-type-body-strong text-fg">
          {t("booking.done.reference", { reference: done.reference })}
        </p>
        <div className="flex flex-col">
          <span className="bv-type-body text-fg">
            {dateText(done.booking.start).primary} · {timeText(done.booking.start).primary}
          </span>
          <span className="bv-type-caption text-fg-muted">
            {dateText(done.booking.start).secondary} · {timeText(done.booking.start).secondary}
          </span>
        </div>
        <ul className="divide-y divide-border border-y border-border">
          {done.booking.items.map((i) => (
            <li key={i.variantId + i.start} className="flex justify-between gap-4 py-3">
              <span className="flex flex-col">
                <span className="bv-type-body text-fg">
                  {itemName(i.variantId, i.serviceName, i.variantName)}
                </span>
                <span className="bv-type-caption text-fg-muted">
                  {t("booking.review.with", { name: i.staffName })}
                </span>
              </span>
              <span className="bv-type-body text-fg">
                {price(
                  i.priceMinor + i.addons.reduce((s, a) => s + a.priceMinor, 0),
                  i.currency,
                  language,
                )}
              </span>
            </li>
          ))}
        </ul>
        <p className="bv-type-body text-fg-muted">
          {requested ? t("booking.done.requestMessage") : t("booking.done.message")}
        </p>
        <div className="flex flex-col gap-3 sm:flex-row">
          <Link href={`/${language}/account/bookings`} className={primaryButton}>
            {t("booking.done.viewBookings")}
          </Link>
          <Link href={venueHref} className={secondaryButton}>
            {t("booking.done.backToVenue", { name: venue.businessName })}
          </Link>
        </div>
      </main>
    );
  }

  return (
    <div className="flex flex-1 flex-col">
      <header className="sticky top-0 z-20 border-b border-border bg-background">
        <div className="mx-auto flex w-full max-w-6xl items-center justify-between gap-4 px-4 py-2 sm:px-6">
          {index > 0 ? (
            <button type="button" onClick={back} className={`${secondaryButton} border-0 px-2`}>
              ‹ {t("booking.back")}
            </button>
          ) : (
            <span />
          )}
          <span className="bv-type-caption text-fg-muted">
            {t("booking.step", { n: index + 1, total: steps.length })}
          </span>
          <Link href={venueHref} className={`${secondaryButton} border-0 px-2`}>
            {t("booking.close")}
          </Link>
        </div>
      </header>

      <div className="mx-auto grid w-full max-w-6xl flex-1 gap-8 px-4 pt-6 pb-32 sm:px-6 lg:grid-cols-[minmax(0,1fr)_22rem] lg:pb-12">
        <main className="flex min-w-0 flex-col gap-6">
          {error ? (
            <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
              {error}
            </p>
          ) : null}

          {step === "services" ? (
            <ServicesStep
              venue={venue}
              language={language}
              picked={picked}
              setPicked={setPicked}
              t={t}
            />
          ) : null}

          {step === "pro" ? (
            <section className="flex flex-col gap-3">
              <h1 className="bv-type-title1 text-fg">{t("booking.pro.title")}</h1>
              <fieldset className="divide-y divide-border border-y border-border">
                <legend className="sr-only">{t("booking.pro.title")}</legend>
                {[
                  { id: null, name: t("booking.pro.anyone"), hint: t("booking.pro.anyoneHint") },
                  ...pros.map((p) => ({ id: p.id, name: p.displayName, hint: p.jobTitle ?? "" })),
                ].map((option) => (
                  <label
                    key={option.id ?? "any"}
                    className="flex min-h-14 cursor-pointer items-center gap-4 py-3"
                  >
                    <input
                      type="radio"
                      name="pro"
                      className="size-5 accent-[var(--bv-color-primary)]"
                      checked={staffId === option.id}
                      onChange={() => setStaffId(option.id)}
                    />
                    <span className="flex flex-col">
                      <span className="bv-type-body text-fg">{option.name}</span>
                      {option.hint ? (
                        <span className="bv-type-caption text-fg-muted">{option.hint}</span>
                      ) : null}
                    </span>
                  </label>
                ))}
              </fieldset>
            </section>
          ) : null}

          {step === "time" ? (
            <section className="flex flex-col gap-4">
              <h1 className="bv-type-title1 text-fg">{t("booking.time.title")}</h1>
              {otherZone ? (
                <p className="bv-type-caption text-fg-muted">{t("booking.time.zone")}</p>
              ) : null}
              <div className="flex items-center justify-between gap-2">
                <button
                  type="button"
                  className={`${secondaryButton} border-0 px-2`}
                  disabled={weekStart <= firstDay}
                  onClick={() => {
                    const previous =
                      addDays(weekStart, -7) < firstDay ? firstDay : addDays(weekStart, -7);
                    setWeekStart(previous);
                    setDay(null);
                    setChosen(null);
                  }}
                >
                  ‹ {t("booking.time.earlierWeek")}
                </button>
                <button
                  type="button"
                  className={`${secondaryButton} border-0 px-2`}
                  onClick={() => {
                    setWeekStart(addDays(weekStart, 7));
                    setDay(null);
                    setChosen(null);
                  }}
                >
                  {t("booking.time.laterWeek")} ›
                </button>
              </div>
              <DayStrip
                from={weekStart}
                selected={day}
                language={language}
                withTimes={slots ? daysWithTimes : null}
                onSelect={(d) => {
                  setDay(d);
                  setChosen(null);
                }}
              />
              {slots == null ? (
                <div aria-busy="true" className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                  <span className="sr-only">{t("booking.time.loading")}</span>
                  {Array.from({ length: 8 }, (_, i) => (
                    <div key={i} className="h-14 rounded-lg bg-surface motion-safe:animate-pulse" />
                  ))}
                </div>
              ) : daySlots.length === 0 ? (
                <div className="flex flex-col items-start gap-3">
                  <p className="bv-type-body text-fg-muted">
                    {daysWithTimes.size === 0
                      ? t("booking.time.noneThisWeek")
                      : t("booking.time.none")}
                  </p>
                  {nextDay ? (
                    <button
                      type="button"
                      className={secondaryButton}
                      onClick={() => setDay(nextDay)}
                    >
                      {t("booking.time.next", {
                        date: formatDualDate(
                          {
                            year: Number(nextDay.slice(0, 4)),
                            month: Number(nextDay.slice(5, 7)),
                            day: Number(nextDay.slice(8, 10)),
                          },
                          prefs,
                          { weekday: true },
                        ).primary,
                      })}
                    </button>
                  ) : daysWithTimes.size === 0 ? (
                    <button
                      type="button"
                      className={secondaryButton}
                      onClick={() => {
                        setWeekStart(addDays(weekStart, 7));
                        setDay(null);
                      }}
                    >
                      {t("booking.time.laterWeek")} ›
                    </button>
                  ) : null}
                  {picked[0] ? (
                    <a
                      href={`${venueHref}/waitlist?variant=${picked[0].variantId}&day=${day}${
                        choices[0]?.staffId ? `&staff=${choices[0].staffId}` : ""
                      }`}
                      className={secondaryButton}
                    >
                      {t("waitlist.join")}
                    </a>
                  ) : null}
                </div>
              ) : (
                <TimeGrid
                  slots={daySlots}
                  timeZone={venue.timezone}
                  language={language}
                  chosen={chosen}
                  onChoose={setChosen}
                />
              )}
            </section>
          ) : null}

          {step === "review" && hold ? (
            <section className="flex flex-col gap-6">
              <h1 className="bv-type-title1 text-fg">{t("booking.review.title")}</h1>
              <ul className="divide-y divide-border border-y border-border">
                {hold.booking.items.map((i) => (
                  <li key={i.variantId + i.start} className="flex justify-between gap-4 py-3">
                    <span className="flex flex-col">
                      <span className="bv-type-body text-fg">
                        {itemName(i.variantId, i.serviceName, i.variantName)}
                      </span>
                      <span className="bv-type-caption text-fg-muted">
                        {timeText(i.start).primary} ·{" "}
                        {t("booking.review.with", { name: i.staffName })}
                      </span>
                    </span>
                    <span className="bv-type-body text-fg">
                      {price(
                        i.priceMinor + i.addons.reduce((s, a) => s + a.priceMinor, 0),
                        i.currency,
                        language,
                      )}
                    </span>
                  </li>
                ))}
              </ul>
              <ul className="bv-type-body flex flex-col gap-2 text-fg">
                {hold.booking.policy.allowPayAtVenue ? (
                  <li>{t("booking.review.payAtVenue")}</li>
                ) : null}
                {hold.booking.policy.cancellationWindowMinutes > 0 ? (
                  <li>
                    {t("booking.review.freeUntil", {
                      time: `${dateText(hold.booking.policy.freeCancellationUntil).primary}, ${
                        timeText(hold.booking.policy.freeCancellationUntil).primary
                      }`,
                    })}
                  </li>
                ) : null}
                {hold.booking.policy.cancellationWindowMinutes > 0 &&
                hold.booking.policy.lateCancelFeePercent ? (
                  <li>
                    {t("booking.review.lateFee", {
                      percent: hold.booking.policy.lateCancelFeePercent,
                    })}
                  </li>
                ) : null}
                {requiresApproval ? <li>{t("booking.review.request")}</li> : null}
              </ul>
              <p className="bv-type-caption text-fg-muted" aria-live="polite">
                {expired
                  ? t("booking.review.expired")
                  : t("booking.review.held", { time: timeText(hold.expiresAt).primary })}
              </p>
              <label className="flex flex-col gap-2">
                <span className="bv-type-label text-fg">{t("booking.review.note")}</span>
                <textarea
                  value={note}
                  maxLength={500}
                  onChange={(e) => setNote(e.target.value)}
                  rows={3}
                  className={`${input} py-2`}
                />
              </label>

              {askName ? (
                <div className="flex flex-col gap-3">
                  <h2 className="bv-type-title2 text-fg">{t("signIn.name.title")}</h2>
                  <label className="flex flex-col gap-2">
                    <span className="bv-type-label text-fg">{t("signIn.name.given")}</span>
                    <input
                      value={givenName}
                      onChange={(e) => setGivenName(e.target.value)}
                      autoComplete="given-name"
                      maxLength={100}
                      required
                      className={input}
                    />
                  </label>
                  <label className="flex flex-col gap-2">
                    <span className="bv-type-label text-fg">{t("signIn.name.father")}</span>
                    <input
                      value={fatherName}
                      onChange={(e) => setFatherName(e.target.value)}
                      autoComplete="family-name"
                      maxLength={100}
                      aria-describedby="father-name-hint"
                      className={input}
                    />
                    <span id="father-name-hint" className="bv-type-caption text-fg-muted">
                      {t("signIn.name.fatherHint")}
                    </span>
                  </label>
                  <button
                    type="button"
                    className={primaryButton}
                    disabled={pending || expired || !givenName.trim()}
                    onClick={confirm}
                  >
                    {requiresApproval ? t("booking.review.requestBook") : t("booking.review.book")}
                  </button>
                </div>
              ) : signedInAs ? (
                <div className="flex flex-col gap-3">
                  <p className="bv-type-caption text-fg-muted">
                    {t("booking.signedInAs", { phone: signedInAs })}
                  </p>
                  <button
                    type="button"
                    className={primaryButton}
                    disabled={pending || expired}
                    onClick={confirm}
                  >
                    {requiresApproval ? t("booking.review.requestBook") : t("booking.review.book")}
                  </button>
                </div>
              ) : challenge ? (
                <div className="flex flex-col gap-3">
                  <label className="flex flex-col gap-2">
                    <span className="bv-type-label text-fg">
                      {t("booking.signIn.code", { phone: challenge.phone })}
                    </span>
                    <input
                      value={code}
                      onChange={(e) => setCode(e.target.value)}
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      className={input}
                    />
                  </label>
                  <button
                    type="button"
                    className={primaryButton}
                    disabled={pending || expired || code.trim().length < 4}
                    onClick={confirm}
                  >
                    {requiresApproval
                      ? t("booking.signIn.requestWithCode")
                      : t("booking.signIn.bookWithCode")}
                  </button>
                </div>
              ) : (
                <div className="flex flex-col gap-3">
                  <p className="bv-type-body text-fg-muted">{t("booking.signIn.intro")}</p>
                  <label className="flex flex-col gap-2">
                    <span className="bv-type-label text-fg">{t("booking.signIn.phone")}</span>
                    <input
                      value={phone}
                      onChange={(e) => setPhone(e.target.value)}
                      inputMode="tel"
                      autoComplete="tel"
                      placeholder="09…"
                      className={input}
                    />
                  </label>
                  <button
                    type="button"
                    className={primaryButton}
                    disabled={pending || expired || phone.trim().length < 9}
                    onClick={askForCode}
                  >
                    {t("booking.signIn.send")}
                  </button>
                </div>
              )}
            </section>
          ) : null}
        </main>

        <aside className="hidden lg:block">
          <div className="sticky top-20 flex flex-col gap-4 rounded-lg border border-border p-5">
            {summary}
            {continueAction}
          </div>
        </aside>
      </div>

      {continueAction ? (
        <div className="fixed inset-x-0 bottom-0 z-20 border-t border-border bg-background px-4 py-3 lg:hidden">
          <div className="mx-auto flex max-w-6xl items-center justify-between gap-4">
            <span className="flex flex-col">
              <span className="bv-type-body-strong text-fg">
                {lines.length > 0
                  ? varies
                    ? t("venue.from", { price: price(totalMin, currency, language) })
                    : price(totalMin, currency, language)
                  : ""}
              </span>
              <span className="bv-type-caption text-fg-muted">
                {lines.length > 0
                  ? `${t("booking.services.count", { count: lines.length })} · ${formatDuration(minutes, t)}`
                  : t("booking.services.empty")}
              </span>
            </span>
            {continueAction}
          </div>
        </div>
      ) : null}
    </div>
  );
}

function ServicesStep({
  venue,
  language,
  picked,
  setPicked,
  t,
}: {
  venue: Venue;
  language: Language;
  picked: Picked[];
  setPicked: (p: Picked[]) => void;
  t: ReturnType<typeof createTranslator>;
}) {
  const toggle = (service: VenueService) => {
    const existing = picked.find((p) => p.serviceId === service.id);
    setPicked(
      existing
        ? picked.filter((p) => p.serviceId !== service.id)
        : [...picked, { serviceId: service.id, variantId: service.variants[0]!.id, addonIds: [] }],
    );
  };
  const update = (serviceId: string, change: Partial<Picked>) =>
    setPicked(picked.map((p) => (p.serviceId === serviceId ? { ...p, ...change } : p)));
  const named = venue.groups.filter((g) => g.id);
  return (
    <section className="flex flex-col gap-6">
      <h1 className="bv-type-title1 text-fg">{t("booking.services.title")}</h1>
      {venue.groups.map((group) => (
        <div key={group.id ?? "other"} className="flex flex-col gap-2">
          {group.id || named.length > 0 ? (
            <h2 className="bv-type-body-strong text-fg">
              {group.id ? pick(group.name, language) : t("venue.otherServices")}
            </h2>
          ) : null}
          <ul className="divide-y divide-border border-y border-border">
            {group.services.map((service) => {
              const choice = picked.find((p) => p.serviceId === service.id);
              const name = pick(service.name, language);
              return (
                <li key={service.id} className="flex flex-col gap-3 py-3">
                  <div className="flex items-center justify-between gap-4">
                    <span className="flex min-w-0 flex-col">
                      <span className="bv-type-body text-fg">{name}</span>
                      <span className="bv-type-caption text-fg-muted">
                        {formatDuration(service.variants[0]!.durationMinutes, t)} ·{" "}
                        {priceText(service.variants, language, t)}
                      </span>
                    </span>
                    <button
                      type="button"
                      aria-pressed={choice != null}
                      aria-label={`${choice ? t("booking.services.remove") : t("booking.services.add")} ${name}`}
                      onClick={() => toggle(service)}
                      className={`bv-type-label inline-flex min-h-12 shrink-0 items-center rounded-lg px-4 focus-visible:outline-2 focus-visible:outline-focus ${
                        choice
                          ? "border border-primary bg-primary-subtle text-fg"
                          : "border border-border-strong text-fg hover:bg-surface"
                      }`}
                    >
                      {choice ? t("booking.services.added") : t("booking.services.add")}
                    </button>
                  </div>
                  {choice && service.variants.length > 1 ? (
                    <fieldset className="flex flex-col gap-1 ps-1">
                      <legend className="sr-only">{name}</legend>
                      {service.variants.map((v) => (
                        <label
                          key={v.id}
                          className="flex min-h-12 cursor-pointer items-center gap-3"
                        >
                          <input
                            type="radio"
                            name={`variant-${service.id}`}
                            className="size-5 accent-[var(--bv-color-primary)]"
                            checked={choice.variantId === v.id}
                            onChange={() => update(service.id, { variantId: v.id })}
                          />
                          <span className="bv-type-body flex-1 text-fg">
                            {pick(v.name, language) || formatDuration(v.durationMinutes, t)}
                          </span>
                          <span className="bv-type-caption text-fg-muted">
                            {formatDuration(v.durationMinutes, t)} · {priceText([v], language, t)}
                          </span>
                        </label>
                      ))}
                    </fieldset>
                  ) : null}
                  {choice && service.addOns.length > 0 ? (
                    <fieldset className="flex flex-col gap-1 ps-1">
                      <legend className="bv-type-label mb-1 text-fg-muted">
                        {t("venue.addons")}
                      </legend>
                      {service.addOns.map((a) => (
                        <label
                          key={a.id}
                          className="flex min-h-12 cursor-pointer items-center gap-3"
                        >
                          <input
                            type="checkbox"
                            className="size-5 accent-[var(--bv-color-primary)]"
                            checked={choice.addonIds.includes(a.id)}
                            onChange={(e) =>
                              update(service.id, {
                                addonIds: e.target.checked
                                  ? [...choice.addonIds, a.id]
                                  : choice.addonIds.filter((x) => x !== a.id),
                              })
                            }
                          />
                          <span className="bv-type-body flex-1 text-fg">
                            {pick(a.name, language)}
                          </span>
                          <span className="bv-type-caption text-fg-muted">
                            {a.durationMinutes > 0
                              ? `+ ${formatDuration(a.durationMinutes, t)} · `
                              : ""}
                            + {price(a.priceMinor, a.currency, language)}
                          </span>
                        </label>
                      ))}
                    </fieldset>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </section>
  );
}
