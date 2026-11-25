"use client";

import type { Schemas } from "@beavent/api-client";
import {
  defaultDisplayPreferences,
  formatDualDate,
  formatStandardTime,
  zoned,
} from "@beavent/calendar";
import { createTranslator, formatDuration, type Language } from "@beavent/i18n";
import { calendarLanguage, localDateOf, localToInstant, price } from "@beavent/venue";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import {
  blockTime,
  bookAtDesk,
  cancelAtDesk,
  findCustomers,
  moveAppointment,
  removeBlock,
} from "./actions";

/** A bookable option for the desk: a service variant and who does it. */
export interface ServiceOption {
  variantId: string;
  label: string;
  durationMinutes: number;
  staffIds: string[];
}

const field =
  "bv-type-body min-h-10 w-full rounded-lg border border-border-strong bg-background px-3 text-fg focus-visible:outline-2 focus-visible:outline-focus";
const primary =
  "bv-type-label inline-flex min-h-10 items-center justify-center rounded-lg bg-primary px-4 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";
const secondary =
  "bv-type-label inline-flex min-h-10 items-center justify-center rounded-lg border border-border-strong px-4 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus";
const panel = "flex flex-col gap-4 rounded-lg border border-border p-4 lg:sticky lg:top-4 lg:w-80";

function Label({ text, children }: { text: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="bv-type-label text-fg">{text}</span>
      {children}
    </label>
  );
}

/** Shows the busy warning with "Book anyway", or an error, after a failed attempt. */
function Problem({
  language,
  busy,
  error,
  onAnyway,
  pending,
}: {
  language: Language;
  busy: boolean;
  error: string | null;
  onAnyway: () => void;
  pending: boolean;
}) {
  const t = createTranslator(language);
  if (busy) {
    return (
      <div role="alert" className="flex flex-col gap-2 rounded-lg bg-warning-subtle px-3 py-2">
        <p className="bv-type-body text-fg">{t("portal.calendar.busy")}</p>
        <button type="button" className={secondary} disabled={pending} onClick={onAnyway}>
          {t("portal.calendar.bookAnyway")}
        </button>
      </div>
    );
  }
  return error ? (
    <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-3 py-2 text-fg">
      {error}
    </p>
  ) : null;
}

/** An empty slot was chosen: book a walk-in or phone customer there, or block the time (P-12). */
export function NewPanel({
  language,
  orgId,
  locationId,
  staff,
  start,
  zone,
  services,
  team,
  onClose,
}: {
  language: Language;
  orgId: string;
  locationId: string;
  staff: { id: string; name: string };
  start: string;
  zone: string;
  services: ServiceOption[];
  team: { id: string; name: string }[];
  onClose: () => void;
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const [mode, setMode] = useState<"appointment" | "block">("appointment");
  // Repeating (a series) or more people at the same time (a group); not both.
  const [every, setEvery] = useState(0);
  const [count, setCount] = useState(4);
  const [skipTaken, setSkipTaken] = useState(false);
  const [guests, setGuests] = useState<
    { name: string; phone: string; variantId: string; staffId: string }[]
  >([]);
  const [notice, setNotice] = useState<string | null>(null);
  const options = services.filter((s) => s.staffIds.includes(staff.id));
  const [variantId, setVariantId] = useState(options[0]?.variantId ?? "");
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<{ id: string; name: string; phone?: string | null }[]>([]);
  const [customerId, setCustomerId] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [note, setNote] = useState("");
  const [source, setSource] = useState<"WALK_IN" | "PHONE">("WALK_IN");
  const [notify, setNotify] = useState(true);
  const [reason, setReason] = useState<"BREAK" | "MEETING" | "TRAINING" | "PERSONAL" | "OTHER">(
    "BREAK",
  );
  const [length, setLength] = useState(30);
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, run] = useTransition();
  const z = zoned(start, zone);

  useEffect(() => {
    if (query.trim().length < 2) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      findCustomers(language, orgId, query).then((r) => !cancelled && setHits(r));
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [language, orgId, query]);

  const book = (anyway: boolean) =>
    run(async () => {
      const e164 = (raw: string) =>
        raw.trim() ? raw.replace(/[^\d+]/g, "").replace(/^0(?=[79]\d{8}$)/, "+251") : undefined;
      const phoneE164 = e164(phone);
      const r = await bookAtDesk(language, orgId, {
        locationId,
        start,
        variantId,
        staffId: staff.id,
        ...(customerId ? { customerId } : { name: name.trim(), phone: phoneE164 }),
        note: note.trim() || undefined,
        source,
        notifyCustomer: notify,
        bookAnyway: anyway,
        guests: guests.map((g) => ({
          name: g.name.trim(),
          phone: e164(g.phone),
          variantId: g.variantId,
          staffId: g.staffId,
        })),
        ...(every > 0 ? { repeat: { everyWeeks: every, count }, skipTaken } : {}),
      });
      if (r.ok) {
        router.refresh();
        if (r.skipped && r.skipped.length > 0) {
          const prefs = defaultDisplayPreferences(calendarLanguage(language), zone);
          setNotice(
            t("portal.calendar.repeat.skipped", {
              count: r.skipped.length,
              dates: r.skipped
                .map((d) => formatDualDate(d, prefs, { weekday: true }).primary)
                .join(language === "am" ? "፣ " : ", "),
            }),
          );
        } else onClose();
      } else if (r.code === "SLOT_TAKEN") setBusy(true);
      else setError(r.message || t("booking.error"));
    });

  const block = () =>
    run(async () => {
      const end = new Date(new Date(start).getTime() + length * 60_000).toISOString();
      const r = await blockTime(language, orgId, {
        locationId,
        staffId: staff.id,
        start,
        end,
        reason,
        title: title.trim() || undefined,
      });
      if (r.ok) {
        router.refresh();
        onClose();
      } else setError(r.message || t("booking.error"));
    });

  return (
    <aside
      aria-label={t("portal.calendar.newTitle", { time: formatStandardTime(z.hour, z.minute) })}
      className={panel}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col">
          <h2 className="bv-type-title2 text-fg">
            {t("portal.calendar.newTitle", { time: formatStandardTime(z.hour, z.minute) })}
          </h2>
          <span className="bv-type-caption text-fg-muted">{staff.name}</span>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="bv-type-label min-h-10 px-2 text-fg-muted hover:text-fg"
        >
          {t("portal.calendar.close")}
        </button>
      </div>
      <div className="flex gap-1 rounded-lg bg-surface p-1" role="tablist">
        {(["appointment", "block"] as const).map((m) => (
          <button
            key={m}
            type="button"
            role="tab"
            aria-selected={mode === m}
            onClick={() => setMode(m)}
            className={`bv-type-label min-h-10 flex-1 rounded-md ${mode === m ? "bg-background text-fg ring-1 ring-border-strong" : "text-fg-muted"}`}
          >
            {m === "appointment"
              ? t("portal.calendar.newAppointment")
              : t("portal.calendar.blockTime")}
          </button>
        ))}
      </div>

      {mode === "appointment" ? (
        <div className="flex flex-col gap-3">
          <Label text={t("portal.calendar.service")}>
            <select
              value={variantId}
              onChange={(e) => setVariantId(e.target.value)}
              className={field}
            >
              {options.map((o) => (
                <option key={o.variantId} value={o.variantId}>
                  {o.label} · {formatDuration(o.durationMinutes, t)}
                </option>
              ))}
            </select>
          </Label>
          <Label text={t("portal.calendar.findCustomer")}>
            <input
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setCustomerId(null);
                if (e.target.value.trim().length < 2) setHits([]);
              }}
              className={field}
            />
          </Label>
          {hits.length > 0 ? (
            <ul className="divide-y divide-border border-y border-border">
              {hits.map((h) => (
                <li key={h.id}>
                  <label className="flex min-h-10 cursor-pointer items-center gap-2 py-1">
                    <input
                      type="radio"
                      name="customer"
                      checked={customerId === h.id}
                      onChange={() => setCustomerId(h.id)}
                      className="size-4 accent-[var(--bv-color-primary)]"
                    />
                    <span className="bv-type-body text-fg">{h.name}</span>
                    {h.phone ? (
                      <span className="bv-type-caption text-fg-muted">{h.phone}</span>
                    ) : null}
                  </label>
                </li>
              ))}
            </ul>
          ) : null}
          {!customerId ? (
            <fieldset className="flex flex-col gap-2">
              <legend className="bv-type-caption mb-1 text-fg-muted">
                {t("portal.calendar.newCustomer")}
              </legend>
              <Label text={t("portal.calendar.customerName")}>
                <input value={name} onChange={(e) => setName(e.target.value)} className={field} />
              </Label>
              <Label text={t("portal.calendar.customerPhone")}>
                <input
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  inputMode="tel"
                  placeholder="09…"
                  className={field}
                />
              </Label>
            </fieldset>
          ) : null}
          <div className="flex gap-4">
            {(["WALK_IN", "PHONE"] as const).map((s) => (
              <label key={s} className="flex min-h-10 items-center gap-2">
                <input
                  type="radio"
                  name="source"
                  checked={source === s}
                  onChange={() => setSource(s)}
                  className="size-4 accent-[var(--bv-color-primary)]"
                />
                <span className="bv-type-body text-fg">
                  {s === "WALK_IN" ? t("portal.calendar.walkIn") : t("portal.calendar.byPhone")}
                </span>
              </label>
            ))}
          </div>
          <Label text={t("portal.calendar.teamNote")}>
            <input value={note} onChange={(e) => setNote(e.target.value)} className={field} />
          </Label>
          {guests.map((g, i) => {
            const set = (change: Partial<typeof g>) =>
              setGuests(guests.map((x, j) => (j === i ? { ...x, ...change } : x)));
            const doers = team.filter((m) =>
              services.find((o) => o.variantId === g.variantId)?.staffIds.includes(m.id),
            );
            return (
              <fieldset key={i} className="flex flex-col gap-2 border-t border-border pt-3">
                <legend className="bv-type-label text-fg">
                  {t("portal.calendar.guest", { n: i + 2 })}
                </legend>
                <Label text={t("portal.calendar.customerName")}>
                  <input
                    value={g.name}
                    onChange={(e) => set({ name: e.target.value })}
                    className={field}
                  />
                </Label>
                <Label text={t("portal.calendar.customerPhone")}>
                  <input
                    value={g.phone}
                    onChange={(e) => set({ phone: e.target.value })}
                    inputMode="tel"
                    placeholder="09…"
                    className={field}
                  />
                </Label>
                <Label text={t("portal.calendar.service")}>
                  <select
                    value={g.variantId}
                    onChange={(e) => {
                      const option = services.find((o) => o.variantId === e.target.value);
                      set({
                        variantId: e.target.value,
                        staffId: option?.staffIds.includes(g.staffId)
                          ? g.staffId
                          : (option?.staffIds[0] ?? ""),
                      });
                    }}
                    className={field}
                  >
                    {services.map((o) => (
                      <option key={o.variantId} value={o.variantId}>
                        {o.label} · {formatDuration(o.durationMinutes, t)}
                      </option>
                    ))}
                  </select>
                </Label>
                <Label text={t("portal.calendar.person")}>
                  <select
                    value={g.staffId}
                    onChange={(e) => set({ staffId: e.target.value })}
                    className={field}
                  >
                    {doers.map((m) => (
                      <option key={m.id} value={m.id}>
                        {m.name}
                      </option>
                    ))}
                  </select>
                </Label>
                <button
                  type="button"
                  className={`${secondary} self-start`}
                  onClick={() => setGuests(guests.filter((_, j) => j !== i))}
                >
                  {t("portal.calendar.remove")}
                </button>
              </fieldset>
            );
          })}
          {every === 0 && guests.length < 9 ? (
            <button
              type="button"
              className={`${secondary} self-start`}
              onClick={() => {
                const option = services.find((o) => o.staffIds.some((id) => id !== staff.id));
                const first = option ?? services[0];
                setGuests([
                  ...guests,
                  {
                    name: "",
                    phone: "",
                    variantId: first?.variantId ?? "",
                    staffId:
                      first?.staffIds.find((id) => id !== staff.id) ?? first?.staffIds[0] ?? "",
                  },
                ]);
              }}
            >
              {t("portal.calendar.addPerson")}
            </button>
          ) : null}
          {guests.length === 0 ? (
            <div className="flex flex-col gap-2">
              <Label text={t("portal.calendar.repeat")}>
                <select
                  value={every}
                  onChange={(e) => setEvery(Number(e.target.value))}
                  className={field}
                >
                  <option value={0}>{t("portal.calendar.repeat.none")}</option>
                  {[1, 2, 3, 4].map((n) => (
                    <option key={n} value={n}>
                      {t("portal.calendar.repeat.every", { count: n })}
                    </option>
                  ))}
                </select>
              </Label>
              {every > 0 ? (
                <>
                  <Label text={t("portal.calendar.repeat.times")}>
                    <select
                      value={count}
                      onChange={(e) => setCount(Number(e.target.value))}
                      className={field}
                    >
                      {Array.from({ length: 11 }, (_, i) => i + 2).map((n) => (
                        <option key={n} value={n}>
                          {n}
                        </option>
                      ))}
                    </select>
                  </Label>
                  <label className="flex min-h-10 items-center gap-2">
                    <input
                      type="checkbox"
                      checked={skipTaken}
                      onChange={(e) => setSkipTaken(e.target.checked)}
                      className="size-4 accent-[var(--bv-color-primary)]"
                    />
                    <span className="bv-type-body text-fg">{t("portal.calendar.repeat.skip")}</span>
                  </label>
                </>
              ) : null}
            </div>
          ) : null}
          <label className="flex min-h-10 items-center gap-2">
            <input
              type="checkbox"
              checked={notify}
              onChange={(e) => setNotify(e.target.checked)}
              className="size-4 accent-[var(--bv-color-primary)]"
            />
            <span className="bv-type-body text-fg">{t("portal.calendar.notify")}</span>
          </label>
          <Problem
            language={language}
            busy={busy}
            error={error}
            pending={pending}
            onAnyway={() => book(true)}
          />
          {notice ? (
            <div role="status" className="flex flex-col gap-2 rounded-lg bg-info-subtle px-3 py-2">
              <p className="bv-type-body text-fg">{notice}</p>
              <button type="button" className={secondary} onClick={onClose}>
                {t("portal.calendar.done")}
              </button>
            </div>
          ) : !busy ? (
            <button
              type="button"
              className={primary}
              disabled={
                pending ||
                !variantId ||
                (!customerId && name.trim().length === 0) ||
                guests.some((g) => !g.name.trim() || !g.staffId)
              }
              onClick={() => book(false)}
            >
              {t("portal.calendar.book")}
            </button>
          ) : null}
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <Label text={t("portal.calendar.reason")}>
            <select
              value={reason}
              onChange={(e) => setReason(e.target.value as typeof reason)}
              className={field}
            >
              {(["BREAK", "MEETING", "TRAINING", "PERSONAL", "OTHER"] as const).map((r) => (
                <option key={r} value={r}>
                  {t(`portal.calendar.reason.${r}`)}
                </option>
              ))}
            </select>
          </Label>
          <Label text={t("portal.calendar.length")}>
            <select
              value={length}
              onChange={(e) => setLength(Number(e.target.value))}
              className={field}
            >
              {[15, 30, 45, 60, 90, 120, 180, 240].map((m) => (
                <option key={m} value={m}>
                  {formatDuration(m, t)}
                </option>
              ))}
            </select>
          </Label>
          <Label text={t("portal.calendar.blockTitle")}>
            <input value={title} onChange={(e) => setTitle(e.target.value)} className={field} />
          </Label>
          <Problem
            language={language}
            busy={false}
            error={error}
            pending={pending}
            onAnyway={() => undefined}
          />
          <button type="button" className={primary} disabled={pending} onClick={block}>
            {t("portal.calendar.save")}
          </button>
        </div>
      )}
    </aside>
  );
}

/** Move an appointment: a new day and time, and optionally another person (S-12's counterpart for the desk). */
export function MoveForm({
  language,
  orgId,
  appointment,
  zone,
  staff,
}: {
  language: Language;
  orgId: string;
  appointment: Schemas["CalendarAppointment"];
  zone: string;
  staff: { id: string; name: string }[];
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const z = zoned(appointment.start, zone);
  const [open, setOpen] = useState(false);
  const [date, setDate] = useState(localDateOf(appointment.start, zone));
  const [time, setTime] = useState(formatStandardTime(z.hour, z.minute));
  const current = appointment.items[0]?.staffId ?? "";
  const [person, setPerson] = useState(current);
  const [notify, setNotify] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, run] = useTransition();
  const move = (anyway: boolean) =>
    run(async () => {
      const r = await moveAppointment(language, orgId, appointment.id, {
        start: localToInstant(date, time, zone),
        ...(person && person !== current ? { staffId: person } : {}),
        notifyCustomer: notify,
        bookAnyway: anyway,
      });
      if (r.ok) {
        setOpen(false);
        router.refresh();
      } else if (r.code === "SLOT_TAKEN") setBusy(true);
      else setError(r.message || t("booking.error"));
    });
  if (!open) {
    return (
      <button type="button" className={secondary} onClick={() => setOpen(true)}>
        {t("portal.calendar.move")}
      </button>
    );
  }
  return (
    <div className="flex w-full flex-col gap-3 border-t border-border pt-3">
      <fieldset className="flex flex-col gap-1">
        <legend className="bv-type-label mb-1 text-fg">{t("portal.calendar.moveTo")}</legend>
        <div className="flex gap-2">
          <input
            type="date"
            aria-label={t("portal.calendar.moveTo")}
            value={date}
            onChange={(e) => setDate(e.target.value)}
            className={field}
          />
          <input
            type="time"
            aria-label={t("portal.calendar.moveTo")}
            step={300}
            value={time}
            onChange={(e) => setTime(e.target.value)}
            className={field}
          />
        </div>
      </fieldset>
      {appointment.items.length === 1 ? (
        <Label text={t("portal.calendar.person")}>
          <select value={person} onChange={(e) => setPerson(e.target.value)} className={field}>
            {staff.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </Label>
      ) : null}
      {appointment.payment && appointment.payment.paidMinor > 0 ? (
        <p className="bv-type-body text-fg-muted">
          {t("portal.calendar.cancelRefund", {
            amount: price(appointment.payment.paidMinor, appointment.payment.currency, language),
          })}
        </p>
      ) : null}
      <label className="flex min-h-10 items-center gap-2">
        <input
          type="checkbox"
          checked={notify}
          onChange={(e) => setNotify(e.target.checked)}
          className="size-4 accent-[var(--bv-color-primary)]"
        />
        <span className="bv-type-body text-fg">{t("portal.calendar.notify")}</span>
      </label>
      <Problem
        language={language}
        busy={busy}
        error={error}
        pending={pending}
        onAnyway={() => move(true)}
      />
      {!busy ? (
        <button type="button" className={primary} disabled={pending} onClick={() => move(false)}>
          {t("portal.calendar.move")}
        </button>
      ) : null}
    </div>
  );
}

/** A block was chosen: what it is, and Remove. */
export function BlockPanel({
  language,
  orgId,
  block,
  label,
  onClose,
}: {
  language: Language;
  orgId: string;
  block: Schemas["CalendarBlock"];
  label: string;
  onClose: () => void;
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const [pending, run] = useTransition();
  const [error, setError] = useState<string | null>(null);
  return (
    <aside
      aria-label={
        block.title ??
        t(
          `portal.calendar.reason.${block.reason === "TIME_OFF" || block.reason === "MAINTENANCE" ? "OTHER" : block.reason}`,
        )
      }
      className={panel}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col">
          <h2 className="bv-type-title2 text-fg">
            {block.title ??
              t(
                `portal.calendar.reason.${block.reason === "TIME_OFF" || block.reason === "MAINTENANCE" ? "OTHER" : block.reason}`,
              )}
          </h2>
          <span className="bv-type-caption text-fg-muted">{label}</span>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="bv-type-label min-h-10 px-2 text-fg-muted hover:text-fg"
        >
          {t("portal.calendar.close")}
        </button>
      </div>
      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-3 py-2 text-fg">
          {error}
        </p>
      ) : null}
      <button
        type="button"
        className={secondary}
        disabled={pending}
        onClick={() =>
          run(async () => {
            const r = await removeBlock(language, orgId, block.id);
            if (r.ok) {
              router.refresh();
              onClose();
            } else setError(r.message || t("booking.error"));
          })
        }
      >
        {t("portal.calendar.remove")}
      </button>
    </aside>
  );
}

/** Cancels a visit (the business cancels: no fee): this one, this and the following of a series, or the group. */
export function CancelForm({
  language,
  orgId,
  appointment,
}: {
  language: Language;
  orgId: string;
  appointment: Schemas["CalendarAppointment"];
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const scopes: ("ONE" | "FOLLOWING" | "GROUP")[] = [
    "ONE",
    ...(appointment.series && appointment.series.index < appointment.series.count
      ? (["FOLLOWING"] as const)
      : []),
    ...(appointment.groupId ? (["GROUP"] as const) : []),
  ];
  const [open, setOpen] = useState(false);
  const [scope, setScope] = useState<"ONE" | "FOLLOWING" | "GROUP">("ONE");
  const [reason, setReason] = useState("");
  const [notify, setNotify] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pending, run] = useTransition();
  if (!open) {
    return (
      <button type="button" className={secondary} onClick={() => setOpen(true)}>
        {t("portal.calendar.cancelVisit")}
      </button>
    );
  }
  return (
    <div className="flex w-full flex-col gap-3 border-t border-border pt-3">
      {scopes.length > 1 ? (
        <fieldset className="flex flex-col gap-1">
          <legend className="bv-type-label mb-1 text-fg">{t("portal.calendar.cancel")}</legend>
          {scopes.map((s) => (
            <label key={s} className="flex min-h-10 items-center gap-2">
              <input
                type="radio"
                name="cancel-scope"
                checked={scope === s}
                onChange={() => setScope(s)}
                className="size-4 accent-[var(--bv-color-primary)]"
              />
              <span className="bv-type-body text-fg">{t(`portal.calendar.cancel.${s}`)}</span>
            </label>
          ))}
        </fieldset>
      ) : null}
      <Label text={t("portal.calendar.cancelReason")}>
        <input value={reason} onChange={(e) => setReason(e.target.value)} className={field} />
      </Label>
      <label className="flex min-h-10 items-center gap-2">
        <input
          type="checkbox"
          checked={notify}
          onChange={(e) => setNotify(e.target.checked)}
          className="size-4 accent-[var(--bv-color-primary)]"
        />
        <span className="bv-type-body text-fg">{t("portal.calendar.notify")}</span>
      </label>
      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-3 py-2 text-fg">
          {error}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={pending}
          className="bv-type-label inline-flex min-h-10 items-center justify-center rounded-lg bg-danger px-4 text-on-danger hover:opacity-90 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
          onClick={() =>
            run(async () => {
              const r = await cancelAtDesk(language, orgId, appointment.id, {
                scope,
                reason: reason.trim() || undefined,
                notifyCustomer: notify,
              });
              if (r.ok) router.refresh();
              else setError(r.message || t("booking.error"));
            })
          }
        >
          {t("portal.calendar.cancelConfirm")}
        </button>
        <button type="button" className={secondary} onClick={() => setOpen(false)}>
          {t("portal.calendar.keep")}
        </button>
      </div>
    </div>
  );
}
