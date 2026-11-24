"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import { pick, type Venue } from "@beavent/venue";
import Link from "next/link";
import { useState, useTransition } from "react";
import { DayStrip, todayIn } from "@/components/booking/time";
import { PhoneSignIn } from "@/components/customer/PhoneSignIn";
import { joinWaitlist } from "@/lib/customer/actions";

const PARTS = ["MORNING", "AFTERNOON", "EVENING"] as const;
const PART_LABEL = {
  MORNING: "booking.time.morning",
  AFTERNOON: "booking.time.afternoon",
  EVENING: "booking.time.evening",
} as const;
const chip = (on: boolean) =>
  `bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg border px-4 focus-visible:outline-2 focus-visible:outline-focus ${
    on
      ? "border-primary bg-primary-subtle text-fg"
      : "border-border-strong text-fg hover:bg-surface"
  }`;
const input =
  "min-h-12 w-full rounded-lg border border-border-strong bg-background px-3 py-2 text-base text-fg outline-none focus:border-primary focus:ring-1 focus:ring-primary";
const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";

/** The S-15 join form: service, professional, days (two weeks, up to seven), times of day; sign-in inline (DD10). */
export function WaitlistJoin({
  venue,
  language,
  signedIn,
  preselect,
}: {
  venue: Venue;
  language: Language;
  signedIn: boolean;
  preselect: { variantId?: string; staffId?: string; day?: string };
}) {
  const t = createTranslator(language);
  const services = venue.groups.flatMap((g) => g.services);
  const options = services.flatMap((s) =>
    s.variants.map((v) => ({
      serviceId: s.id,
      variantId: v.id,
      label: [pick(s.name, language), pick(v.name, language)].filter(Boolean).join(" · "),
    })),
  );
  const today = todayIn(venue.timezone);
  const [variantId, setVariantId] = useState(
    options.some((o) => o.variantId === preselect.variantId)
      ? preselect.variantId!
      : (options[0]?.variantId ?? ""),
  );
  const serviceId = options.find((o) => o.variantId === variantId)?.serviceId;
  const pros = venue.team.filter((p) => serviceId && p.serviceIds.includes(serviceId));
  const [staffId, setStaffId] = useState<string>(
    preselect.staffId && pros.some((p) => p.id === preselect.staffId) ? preselect.staffId : "",
  );
  const [days, setDays] = useState<Set<string>>(
    () => new Set(preselect.day && preselect.day >= today ? [preselect.day] : []),
  );
  const [parts, setParts] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [pending, start] = useTransition();

  const toggle = (set: Set<string>, value: string, max: number) => {
    const next = new Set(set);
    if (next.has(value)) next.delete(value);
    else if (next.size < max) next.add(value);
    return next;
  };

  if (done)
    return (
      <div className="flex flex-col gap-4">
        <p role="status" className="bv-type-body rounded-lg bg-success-subtle px-4 py-3 text-fg">
          {t("waitlist.joined")}
        </p>
        <Link href={`/${language}/account/bookings`} className={`${primary} self-start`}>
          {t("booking.done.viewBookings")}
        </Link>
      </div>
    );

  return (
    <div className="flex flex-col gap-6">
      <label className="flex flex-col gap-2">
        <span className="bv-type-label text-fg">{t("waitlist.service")}</span>
        <select
          value={variantId}
          onChange={(e) => {
            setVariantId(e.target.value);
            setStaffId("");
          }}
          className={input}
        >
          {options.map((o) => (
            <option key={o.variantId} value={o.variantId}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      {pros.length > 1 ? (
        <label className="flex flex-col gap-2">
          <span className="bv-type-label text-fg">{t("waitlist.pro")}</span>
          <select value={staffId} onChange={(e) => setStaffId(e.target.value)} className={input}>
            <option value="">{t("booking.pro.anyone")}</option>
            {pros.map((p) => (
              <option key={p.id} value={p.id}>
                {p.displayName}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      <fieldset className="flex min-w-0 flex-col gap-2">
        <legend className="bv-type-label mb-2 text-fg">{t("waitlist.days")}</legend>
        <DayStrip
          from={today}
          count={14}
          selected={days}
          language={language}
          withTimes={null}
          onSelect={(d) => setDays(toggle(days, d, 7))}
        />
      </fieldset>
      <fieldset className="flex min-w-0 flex-col gap-2">
        <legend className="bv-type-label mb-2 text-fg">{t("waitlist.parts")}</legend>
        <div className="flex flex-wrap gap-2">
          {PARTS.map((p) => (
            <button
              key={p}
              type="button"
              aria-pressed={parts.has(p)}
              onClick={() => setParts(toggle(parts, p, 3))}
              className={chip(parts.has(p))}
            >
              {t(PART_LABEL[p])}
            </button>
          ))}
        </div>
      </fieldset>
      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
          {error}
        </p>
      ) : null}
      {signedIn ? (
        <button
          type="button"
          disabled={pending}
          className={`${primary} self-start`}
          onClick={() => {
            if (days.size === 0 || parts.size === 0) {
              setError(t("waitlist.choose"));
              return;
            }
            setError(null);
            start(async () => {
              const r = await joinWaitlist(language, {
                locationId: venue.locationId,
                variantId,
                staffId: staffId || null,
                days: [...days].sort(),
                parts: PARTS.filter((p) => parts.has(p)),
              });
              if (r.ok) setDone(true);
              else setError(r.message || t("booking.error"));
            });
          }}
        >
          {t("waitlist.submit")}
        </button>
      ) : (
        <div className="flex flex-col gap-3 border-t border-border pt-6">
          <p className="bv-type-body text-fg">{t("waitlist.signIn")}</p>
          <PhoneSignIn language={language} />
        </div>
      )}
    </div>
  );
}
