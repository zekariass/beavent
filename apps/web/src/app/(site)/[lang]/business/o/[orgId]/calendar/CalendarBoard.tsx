"use client";

import type { Schemas } from "@beavent/api-client";
import { formatEthiopianTime, formatStandardTime, toEthiopianTime, zoned } from "@beavent/calendar";
import { createTranslator, type Language } from "@beavent/i18n";
import { localDateOf, localToInstant, price } from "@beavent/venue";
import { useRouter } from "next/navigation";
import { useEffect, useState, useSyncExternalStore, useTransition } from "react";
import { answerRequest, changeStatus } from "./actions";
import {
  BlockPanel,
  CancelForm,
  MoveForm,
  NewPanel,
  PaymentLinkForm,
  type ServiceOption,
} from "./DeskPanels";

type Calendar = Schemas["Calendar"];
type Appointment = Schemas["CalendarAppointment"];
type Range = { standard: string; ethiopian: string | null };

/** Pixels per minute: a 15-minute slot is 24 px, a 12-hour day about 1,150 px. */
const PX = 1.6;

/** The current minute, ticking once a minute (for the "now" line). */
function useMinute(): number | null {
  return useSyncExternalStore(
    (onChange) => {
      const timer = setInterval(onChange, 30_000);
      return () => clearInterval(timer);
    },
    () => Math.floor(Date.now() / 60_000),
    () => null,
  );
}

/**
 * The day board (P-12): a time gutter and a column per team member. Working time is the page background, time off the
 * surface colour; appointments are tinted blocks with a leading bar (requests in the warning tint), processing stages
 * lighter because the person is free then; breaks and blocks are labelled. Partner tools use the 24-hour clock; Amharic
 * readers also see Ethiopian time (doc 06 §2.1).
 */
export function CalendarBoard({
  language,
  orgId,
  calendar,
  date,
  isToday,
  services,
}: {
  language: Language;
  orgId: string;
  calendar: Calendar;
  date: string;
  isToday: boolean;
  services: ServiceOption[];
}) {
  const t = createTranslator(language);
  const zone = calendar.timezone;
  const [selected, setSelected] = useState<string | null>(null);
  // An empty slot (to book or block there) or a block, when chosen.
  const [slot, setSlot] = useState<{ staffId: string; start: string } | null>(null);
  const [blockId, setBlockId] = useState<string | null>(null);
  const choose = (
    kind: "appointment" | "slot" | "block",
    value: string | { staffId: string; start: string },
  ) => {
    setSelected(kind === "appointment" ? (value as string) : null);
    setBlockId(kind === "block" ? (value as string) : null);
    setSlot(kind === "slot" ? (value as { staffId: string; start: string }) : null);
  };
  const minute = useMinute();

  // Live updates: reload the board when anything changes at this location (another desk, an online booking).
  const router = useRouter();
  useEffect(() => {
    const source = new EventSource(
      `/${language}/business/o/${orgId}/calendar/stream?locationId=${calendar.locationId}`,
    );
    let timer: ReturnType<typeof setTimeout> | undefined;
    source.addEventListener("changed", () => {
      clearTimeout(timer);
      timer = setTimeout(() => router.refresh(), 300);
    });
    return () => {
      clearTimeout(timer);
      source.close();
    };
  }, [language, orgId, calendar.locationId, router]);

  /** Minutes after local midnight of `date` (clamped to the day). */
  const minutesOf = (instant: string) => {
    const day = localDateOf(instant, zone);
    if (day < date) return 0;
    if (day > date) return 24 * 60;
    const z = zoned(instant, zone);
    return z.hour * 60 + z.minute;
  };
  /** "09:00–12:30", and for Amharic readers the same range in Ethiopian time ("ጠዋት 3:00–ከሰዓት 6:30"). */
  const range = (start: string, end: string): Range => {
    const a = zoned(start, zone);
    const b = zoned(end, zone);
    const et = (h: number, m: number) => formatEthiopianTime(toEthiopianTime(h, m), "am");
    return {
      standard: `${formatStandardTime(a.hour, a.minute)}–${formatStandardTime(b.hour, b.minute)}`,
      ethiopian: language === "am" ? `${et(a.hour, a.minute)}–${et(b.hour, b.minute)}` : null,
    };
  };
  /** The service's current name in the reader's language; the booked name otherwise. */
  const nameOf = (item: Schemas["CalendarItem"]) =>
    item.names.service?.[language] ?? item.names.service?.en ?? item.serviceName;

  // Visible hours: 08:00–20:00, widened to whatever is on the calendar.
  const marks = [
    ...calendar.staff.flatMap((s) => s.working.flatMap((w) => [w.start, w.end])),
    ...calendar.appointments.flatMap((a) => [a.start, a.end]),
    ...calendar.blocks.flatMap((b) => [b.start, b.end]),
  ].map(minutesOf);
  const first = Math.min(8 * 60, ...marks.map((m) => Math.floor(m / 60) * 60));
  const last = Math.max(20 * 60, ...marks.map((m) => Math.ceil(m / 60) * 60));
  const height = (last - first) * PX;
  const top = (instant: string) => (minutesOf(instant) - first) * PX;
  const span = (start: string, end: string) =>
    Math.max((minutesOf(end) - minutesOf(start)) * PX, 18);
  const hours = Array.from({ length: (last - first) / 60 }, (_, i) => first + i * 60);
  const nowLocal =
    isToday && minute != null ? minutesOf(new Date(minute * 60_000).toISOString()) : null;

  const open = calendar.appointments.find((a) => a.id === selected) ?? null;

  return (
    <div className="flex flex-col gap-4 lg:flex-row lg:items-start">
      <div className="min-w-0 flex-1 overflow-x-auto rounded-lg border border-border">
        <div className="flex w-max min-w-full">
          {/* Time gutter */}
          <div className="sticky start-0 z-10 w-16 shrink-0 border-e border-border bg-background">
            <div className="h-12 border-b border-border" />
            <div className="relative" style={{ height }}>
              {hours.map((h) => (
                <span
                  key={h}
                  className="bv-type-caption absolute end-2 -translate-y-2 text-fg-muted"
                  style={{ top: (h - first) * PX }}
                >
                  {formatStandardTime(Math.floor(h / 60), 0)}
                </span>
              ))}
            </div>
          </div>

          {calendar.staff.map((person) => {
            const items = calendar.appointments.flatMap((a) =>
              a.items
                .filter((i) => i.staffId === person.staffId)
                .map((i) => ({ appointment: a, item: i })),
            );
            const blocks = calendar.blocks.filter((b) => b.staffId === person.staffId);
            return (
              <div
                key={person.staffId}
                className="min-w-48 flex-1 border-e border-border last:border-e-0"
              >
                <div className="bv-type-label flex h-12 items-center justify-center border-b border-border px-2 text-fg">
                  {person.name}
                </div>
                <div
                  className="relative cursor-pointer bg-surface"
                  style={{ height }}
                  onClick={(e) => {
                    if (e.target !== e.currentTarget && !(e.target as HTMLElement).dataset.slot)
                      return;
                    const y = e.clientY - e.currentTarget.getBoundingClientRect().top;
                    const minutes = first + Math.floor(y / PX / 15) * 15;
                    const hhmm = `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
                    choose("slot", {
                      staffId: person.staffId,
                      start: localToInstant(date, hhmm, zone),
                    });
                  }}
                >
                  {/* Working time on the page background; hour and quarter lines. */}
                  {person.working
                    .filter(
                      (w) =>
                        localDateOf(w.start, zone) === date || localDateOf(w.end, zone) === date,
                    )
                    .map((w) => (
                      <div
                        key={w.start}
                        data-slot="1"
                        className="absolute inset-x-0 bg-background"
                        style={{ top: top(w.start), height: span(w.start, w.end) }}
                      />
                    ))}
                  {hours.map((h) => (
                    <div
                      key={h}
                      data-slot="1"
                      className="absolute inset-x-0 border-t border-border"
                      style={{ top: (h - first) * PX }}
                    />
                  ))}
                  {person.working.length === 0 ? (
                    <p className="bv-type-caption absolute inset-x-0 top-2 text-center text-fg-subtle">
                      {t("portal.calendar.notWorking")}
                    </p>
                  ) : null}
                  {blocks.map((b) => (
                    <button
                      type="button"
                      key={b.id}
                      onClick={() => choose("block", b.id)}
                      aria-pressed={blockId === b.id}
                      className="bv-type-caption absolute inset-x-1 overflow-hidden rounded-md border border-border bg-surface px-2 py-1 text-start text-fg-muted focus-visible:outline-2 focus-visible:outline-focus"
                      style={{ top: top(b.start), height: span(b.start, b.end) }}
                    >
                      {b.title ??
                        t(
                          `portal.calendar.reason.${b.reason === "TIME_OFF" || b.reason === "MAINTENANCE" ? "OTHER" : b.reason}`,
                        )}
                    </button>
                  ))}
                  {items.map(({ appointment, item }) => {
                    const requested = appointment.status === "REQUESTED";
                    const unpaid = appointment.status === "PENDING_PAYMENT";
                    const done =
                      appointment.status === "COMPLETED" || appointment.status === "NO_SHOW";
                    return (
                      <button
                        type="button"
                        key={item.id}
                        onClick={() => choose("appointment", appointment.id)}
                        aria-pressed={selected === appointment.id}
                        className={`absolute inset-x-1 flex flex-col justify-start overflow-hidden rounded-md border-s-[3px] px-2 py-1 text-start focus-visible:outline-2 focus-visible:outline-focus ${
                          requested
                            ? "border-warning bg-warning-subtle"
                            : unpaid
                              ? "border-border-strong bg-surface"
                              : done
                                ? "border-border-strong bg-surface"
                                : "border-primary bg-primary-subtle"
                        }`}
                        style={{ top: top(item.start), height: span(item.start, item.end) }}
                      >
                        {item.stages
                          .filter((s) => !s.staffBusy)
                          .map((s) => (
                            <span
                              key={s.start}
                              aria-hidden="true"
                              className="absolute inset-x-0 bg-background/70"
                              style={{
                                top: top(s.start) - top(item.start),
                                height: span(s.start, s.end),
                              }}
                            />
                          ))}
                        <span className="relative flex flex-col">
                          <span className="bv-type-caption text-fg-muted">
                            {range(item.start, item.end).standard}
                          </span>
                          <span className="bv-type-label flex items-center gap-1.5 text-fg">
                            {appointment.customer.alerts.length > 0 ? (
                              <span
                                aria-hidden
                                className="size-2 shrink-0 rounded-full bg-warning"
                              />
                            ) : null}
                            <span className="truncate">{appointment.customer.name}</span>
                            {appointment.customer.alerts.length > 0 ? (
                              <span className="sr-only">{t("portal.clients.hasAlert")}</span>
                            ) : null}
                          </span>
                          <span className="bv-type-caption text-fg">{nameOf(item)}</span>
                          {appointment.status !== "CONFIRMED" ? (
                            <span className="bv-type-caption text-fg">
                              {t(`status.${appointment.status}`)}
                            </span>
                          ) : null}
                        </span>
                      </button>
                    );
                  })}
                  {nowLocal != null && nowLocal >= first && nowLocal <= last ? (
                    <div
                      aria-hidden="true"
                      className="pointer-events-none absolute inset-x-0 z-10 h-px bg-danger"
                      style={{ top: (nowLocal - first) * PX }}
                    >
                      <span className="absolute -start-1 -top-1 size-2 rounded-full bg-danger" />
                    </div>
                  ) : null}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {calendar.appointments.length === 0 ? (
        <p className="bv-type-body text-fg-muted lg:w-80">{t("portal.calendar.empty")}</p>
      ) : null}

      {slot ? (
        <NewPanel
          key={`${slot.staffId}${slot.start}`}
          language={language}
          orgId={orgId}
          locationId={calendar.locationId}
          staff={{
            id: slot.staffId,
            name: calendar.staff.find((s) => s.staffId === slot.staffId)?.name ?? "",
          }}
          start={slot.start}
          zone={zone}
          services={services}
          team={calendar.staff.map((s) => ({ id: s.staffId, name: s.name }))}
          onClose={() => setSlot(null)}
        />
      ) : null}
      {blockId && calendar.blocks.some((b) => b.id === blockId) ? (
        <BlockPanel
          key={blockId}
          language={language}
          orgId={orgId}
          block={calendar.blocks.find((b) => b.id === blockId)!}
          label={(() => {
            const b = calendar.blocks.find((x) => x.id === blockId)!;
            return `${range(b.start, b.end).standard} · ${calendar.staff.find((s) => s.staffId === b.staffId)?.name ?? ""}`;
          })()}
          onClose={() => setBlockId(null)}
        />
      ) : null}
      {open ? (
        <AppointmentPanel
          key={open.id}
          language={language}
          orgId={orgId}
          appointment={open}
          range={range}
          nameOf={nameOf}
          staffName={(id) => calendar.staff.find((s) => s.staffId === id)?.name ?? ""}
          onClose={() => setSelected(null)}
          zone={zone}
          staff={calendar.staff.map((s) => ({ id: s.staffId, name: s.name }))}
          started={minute != null && minute * 60_000 >= new Date(open.start).getTime()}
          groupNames={
            open.groupId
              ? calendar.appointments
                  .filter((a) => a.groupId === open.groupId)
                  .map((a) => a.customer.name)
              : []
          }
        />
      ) : null}
    </div>
  );
}

function AppointmentPanel({
  language,
  orgId,
  appointment,
  range,
  nameOf,
  staffName,
  onClose,
  started,
  zone,
  staff,
  groupNames,
}: {
  language: Language;
  orgId: string;
  appointment: Appointment;
  range: (start: string, end: string) => Range;
  nameOf: (item: Schemas["CalendarItem"]) => string;
  staffName: (id: string | null | undefined) => string;
  onClose: () => void;
  started: boolean;
  zone: string;
  staff: { id: string; name: string }[];
  groupNames: string[];
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const [pending, start] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const clock = (instant: string) => {
    const z = zoned(instant, zone);
    return formatStandardTime(z.hour, z.minute);
  };
  const run = (action: () => Promise<{ ok: boolean; message?: string }>) =>
    start(async () => {
      const r = await action();
      if (r.ok) router.refresh();
      else setError(r.message ?? t("booking.error"));
    });
  const statusButton = (
    status: "ARRIVED" | "IN_PROGRESS" | "COMPLETED" | "NO_SHOW",
    label: string,
    primary = false,
  ) => (
    <button
      type="button"
      key={status}
      disabled={pending}
      onClick={() => run(() => changeStatus(language, orgId, appointment.id, status))}
      className={`bv-type-label inline-flex min-h-10 items-center justify-center rounded-lg px-4 focus-visible:outline-2 focus-visible:outline-focus ${
        primary
          ? "bg-primary text-on-primary hover:bg-primary-pressed"
          : "border border-border-strong text-fg hover:bg-surface"
      }`}
    >
      {label}
    </button>
  );
  const s = appointment.status;
  return (
    <aside
      aria-label={appointment.customer.name}
      className="flex flex-col gap-4 rounded-lg border border-border p-4 lg:sticky lg:top-4 lg:w-80"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col">
          <span className="bv-type-caption text-fg-muted">{t(`status.${s}`)}</span>
          <h2 className="bv-type-title2 text-fg">
            <a
              href={`/${language}/business/o/${orgId}/clients/${appointment.customer.id}`}
              className="hover:text-primary hover:underline hover:underline-offset-4"
            >
              {appointment.customer.name}
            </a>
          </h2>
          {appointment.customer.phone ? (
            <a
              href={`tel:${appointment.customer.phone}`}
              className="bv-type-body text-primary underline underline-offset-4"
            >
              {appointment.customer.phone}
            </a>
          ) : null}
        </div>
        <button
          type="button"
          onClick={onClose}
          className="bv-type-label inline-flex min-h-10 items-center px-2 text-fg-muted hover:text-fg"
        >
          {t("portal.calendar.close")}
        </button>
      </div>
      {appointment.customer.alerts.length > 0 ? (
        <div role="note" className="flex flex-col gap-1 rounded-lg bg-warning-subtle px-3 py-2">
          {appointment.customer.alerts.map((a, i) => (
            <p key={i} className="bv-type-body text-fg">
              <span className="font-semibold">{t(`portal.clients.note.${a.kind}`)}:</span> {a.body}
            </p>
          ))}
        </div>
      ) : null}
      <div className="flex flex-col">
        <p className="bv-type-body text-fg">
          {range(appointment.start, appointment.end).standard} · {appointment.reference}
        </p>
        {range(appointment.start, appointment.end).ethiopian ? (
          <p className="bv-type-caption text-fg-muted">
            {range(appointment.start, appointment.end).ethiopian}
          </p>
        ) : null}
      </div>
      <ul className="divide-y divide-border border-y border-border">
        {appointment.items.map((i) => (
          <li key={i.id} className="flex flex-col py-2">
            <span className="bv-type-body text-fg">
              {nameOf(i)}
              {(i.names.variant?.[language] ?? i.variantName)
                ? ` · ${i.names.variant?.[language] ?? i.variantName}`
                : ""}
            </span>
            <span className="bv-type-caption text-fg-muted">
              {range(i.start, i.end).standard} · {staffName(i.staffId)}
            </span>
          </li>
        ))}
      </ul>
      {appointment.series || groupNames.length > 1 ? (
        <div className="flex flex-col gap-1">
          {appointment.series ? (
            <p className="bv-type-body text-fg">
              {t("portal.calendar.series", {
                every: appointment.series.everyWeeks,
                index: appointment.series.index,
                count: appointment.series.count,
              })}
            </p>
          ) : null}
          {groupNames.length > 1 ? (
            <p className="bv-type-body text-fg">
              {t("portal.calendar.group", {
                names: groupNames.join(language === "am" ? "፣ " : ", "),
              })}
            </p>
          ) : null}
        </div>
      ) : null}
      {appointment.payment ? (
        <p className="bv-type-body text-fg">
          {appointment.payment.state === "PAID"
            ? t("portal.calendar.depositPaid", {
                paid: price(appointment.payment.paidMinor, appointment.payment.currency, language),
                venue: price(
                  appointment.payment.atVenueMinor,
                  appointment.payment.currency,
                  language,
                ),
              })
            : appointment.payment.state === "AWAITING_PAYMENT"
              ? t("portal.calendar.depositAwaiting", {
                  amount: price(
                    appointment.payment.dueNowMinor,
                    appointment.payment.currency,
                    language,
                  ),
                  time: appointment.payment.expiresAt ? clock(appointment.payment.expiresAt) : "",
                })
              : t("portal.calendar.depositExpired")}
        </p>
      ) : null}
      {appointment.forms.length > 0 ? (
        <ul className="flex flex-col gap-1">
          {appointment.forms.map((f) => (
            <li key={f.formId} className="bv-type-body flex items-center gap-2 text-fg">
              <span
                aria-hidden
                className={`size-2 shrink-0 rounded-full ${f.done ? "bg-success" : "bg-warning"}`}
              />
              {t(f.done ? "portal.calendar.formDone" : "portal.calendar.formMissing", {
                name: f.title[language] ?? f.title.en ?? Object.values(f.title)[0] ?? "",
              })}
            </li>
          ))}
        </ul>
      ) : null}
      {appointment.note ? (
        <div className="flex flex-col gap-1">
          <span className="bv-type-label text-fg-muted">{t("portal.calendar.note")}</span>
          <p className="bv-type-body text-fg">{appointment.note}</p>
        </div>
      ) : null}
      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-3 py-2 text-fg">
          {error}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-2">
        {s === "REQUESTED" ? (
          <>
            <p className="bv-type-body w-full text-fg">{t("portal.calendar.requestWaiting")}</p>
            <button
              type="button"
              disabled={pending}
              onClick={() => run(() => answerRequest(language, orgId, appointment.id, true))}
              className="bv-type-label inline-flex min-h-10 items-center justify-center rounded-lg bg-primary px-4 text-on-primary hover:bg-primary-pressed"
            >
              {t("portal.calendar.accept")}
            </button>
            <button
              type="button"
              disabled={pending}
              onClick={() => run(() => answerRequest(language, orgId, appointment.id, false))}
              className="bv-type-label inline-flex min-h-10 items-center justify-center rounded-lg border border-border-strong px-4 text-fg hover:bg-surface"
            >
              {t("portal.calendar.decline")}
            </button>
          </>
        ) : null}
        {s === "CONFIRMED" ? statusButton("ARRIVED", t("portal.calendar.arrived"), true) : null}
        {s === "CONFIRMED" || s === "ARRIVED"
          ? statusButton("IN_PROGRESS", t("portal.calendar.start"), s === "ARRIVED")
          : null}
        {s === "CONFIRMED" || s === "ARRIVED" || s === "IN_PROGRESS"
          ? statusButton("COMPLETED", t("portal.calendar.complete"), s === "IN_PROGRESS")
          : null}
        {(s === "CONFIRMED" || s === "ARRIVED") && started
          ? statusButton("NO_SHOW", t("portal.calendar.noShow"))
          : null}
        {s === "CONFIRMED" || s === "REQUESTED" || s === "ARRIVED" ? (
          <MoveForm
            language={language}
            orgId={orgId}
            appointment={appointment}
            zone={zone}
            staff={staff}
          />
        ) : null}
        {s === "CONFIRMED" || s === "PENDING_PAYMENT" ? (
          <PaymentLinkForm
            language={language}
            orgId={orgId}
            appointment={appointment}
            zone={zone}
          />
        ) : null}
        {s === "CONFIRMED" || s === "REQUESTED" ? (
          <CancelForm language={language} orgId={orgId} appointment={appointment} />
        ) : null}
      </div>
    </aside>
  );
}
