"use client";

import type { Schemas } from "@beavent/api-client";
import { createTranslator, type Language } from "@beavent/i18n";
import Link from "next/link";
import { useMemo, useState, useTransition } from "react";
import { formatPrice, inputClass, toBirr } from "@/components/business/ui";
import { OpenForm } from "../../cash/CashForms";
import { checkoutVisit } from "../actions";

const PRESETS = [5, 10, 15] as const;

/** Birr as typed to minor units; 0 for an empty or unreadable field. */
function minor(value: string): number {
  const n = Number(value.replace(/,/g, "").trim());
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : 0;
}

/**
 * The counter: what the visit comes to, what was paid online, a tip split between the people who worked on it, the cash
 * received and the change. One primary action takes the cash, completes the visit and issues the receipt.
 */
export function CheckoutForm({
  language,
  orgId,
  bill,
}: {
  language: Language;
  orgId: string;
  bill: Schemas["CheckoutBill"];
}) {
  const t = createTranslator(language);
  const money = (amount: number) => formatPrice(amount, bill.currency, language);
  const [preset, setPreset] = useState<number | "none" | "other">("none");
  const [tips, setTips] = useState<Record<string, string>>({});
  const [cash, setCash] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Schemas["CheckoutDone"] | null>(null);
  const [pending, start] = useTransition();
  const base = `/${language}/business/o/${orgId}`;

  const tipTotal = useMemo(
    () => bill.team.reduce((sum, p) => sum + minor(tips[p.staffId] ?? ""), 0),
    [bill.team, tips],
  );
  const owe = bill.dueMinor + tipTotal;
  const handed = cash === null ? owe : minor(cash);
  const enough = handed >= owe;

  /** A tip as a share of the visit, split evenly between the team (the first person gets the odd coin). */
  const choose = (next: number | "none" | "other") => {
    setPreset(next);
    if (next === "other") return;
    const total = next === "none" ? 0 : Math.round((bill.totalMinor * next) / 100);
    const n = Math.max(bill.team.length, 1);
    const each = Math.floor(total / n);
    const rest = total - each * n;
    setTips(
      Object.fromEntries(
        bill.team.map((p, i) => [
          p.staffId,
          total === 0 ? "" : toBirr(each + (i === 0 ? rest : 0), bill.currency),
        ]),
      ),
    );
    setCash(null);
  };

  if (done) {
    return (
      <section className="flex max-w-xl flex-col gap-4">
        <p role="status" className="bv-type-title2">
          {t("counter.checkout.done")} ·{" "}
          {t("counter.checkout.receipt", { number: done.receiptNumber })}
        </p>
        {done.changeMinor > 0 ? (
          <p className="bv-type-title2 text-fg">
            {t("counter.checkout.giveChange", { amount: money(done.changeMinor) })}
          </p>
        ) : null}
        <ul className="divide-y divide-border border-y border-border">
          <li className="flex justify-between gap-4 py-3">
            <span className="bv-type-body">{t("counter.checkout.total")}</span>
            <span className="bv-type-body tabular-nums">{money(done.totalMinor)}</span>
          </li>
          {done.paidOnlineMinor > 0 ? (
            <li className="flex justify-between gap-4 py-3">
              <span className="bv-type-body">{t("counter.checkout.paidOnline")}</span>
              <span className="bv-type-body tabular-nums">{money(done.paidOnlineMinor)}</span>
            </li>
          ) : null}
          {done.tipMinor > 0 ? (
            <li className="flex justify-between gap-4 py-3">
              <span className="bv-type-body">{t("counter.checkout.tip")}</span>
              <span className="bv-type-body tabular-nums">{money(done.tipMinor)}</span>
            </li>
          ) : null}
        </ul>
        <Link
          href={`${base}/calendar`}
          className="bv-type-label inline-flex min-h-11 items-center self-start underline underline-offset-4"
        >
          {t("counter.checkout.back")}
        </Link>
      </section>
    );
  }

  if (bill.receiptNumber) {
    return (
      <p role="status" className="bv-type-body">
        {t("counter.checkout.alreadyDone", { number: bill.receiptNumber })}
      </p>
    );
  }
  if (!bill.canCheckout) {
    return (
      <p role="status" className="bv-type-body">
        {t("counter.checkout.cannot", { status: bill.status })}
      </p>
    );
  }

  const take = () =>
    start(async () => {
      setError(null);
      const r = await checkoutVisit(
        language,
        orgId,
        bill.appointmentId,
        bill.team.map((p) => ({ staffId: p.staffId, amountMinor: minor(tips[p.staffId] ?? "") })),
        owe > 0 ? handed : null,
      );
      if (r.ok) setDone(r.done);
      else setError(r.message || t("counter.checkout.error"));
    });

  return (
    <div className="flex max-w-xl flex-col gap-6">
      <p className="bv-type-body text-fg-muted">
        {t("counter.checkout.visit", { reference: bill.reference, name: bill.customerName ?? "" })}
      </p>

      <section className="flex flex-col gap-2">
        <h2 className="bv-type-title2">{t("counter.checkout.bill")}</h2>
        <ul className="divide-y divide-border border-y border-border">
          {bill.lines.map((l, i) => (
            <li key={i} className="flex min-h-12 items-center justify-between gap-4 py-2">
              <span className="bv-type-body">{l.description}</span>
              <span className="bv-type-body tabular-nums">{money(l.totalMinor)}</span>
            </li>
          ))}
          {bill.paidMinor > 0 ? (
            <li className="flex min-h-12 items-center justify-between gap-4 py-2">
              <span className="bv-type-body text-fg-muted">{t("counter.checkout.paidOnline")}</span>
              <span className="bv-type-body tabular-nums text-fg-muted">
                −{money(bill.paidMinor)}
              </span>
            </li>
          ) : null}
          <li className="flex min-h-12 items-center justify-between gap-4 py-2">
            <span className="bv-type-body font-medium">{t("counter.checkout.due")}</span>
            <span className="bv-type-body font-medium tabular-nums">{money(bill.dueMinor)}</span>
          </li>
        </ul>
      </section>

      {bill.team.length > 0 ? (
        <section className="flex flex-col gap-3">
          <h2 className="bv-type-title2">{t("counter.checkout.tip")}</h2>
          <div role="group" aria-label={t("counter.checkout.tip")} className="flex flex-wrap gap-3">
            {(["none", ...PRESETS, "other"] as const).map((p) => (
              <button
                key={p}
                type="button"
                aria-pressed={preset === p}
                onClick={() => choose(p)}
                className={`bv-type-label inline-flex min-h-11 min-w-16 items-center justify-center rounded-lg border px-4 focus-visible:outline-2 focus-visible:outline-focus ${
                  preset === p
                    ? "border-primary bg-primary-subtle text-fg"
                    : "border-border-strong text-fg hover:bg-surface"
                }`}
              >
                {p === "none"
                  ? t("counter.checkout.tip.none")
                  : p === "other"
                    ? t("counter.checkout.tip.other")
                    : `${p}%`}
              </button>
            ))}
          </div>
          {preset !== "none"
            ? bill.team.map((p) => (
                <label key={p.staffId} className="flex flex-col gap-1">
                  <span className="bv-type-label">
                    {t("counter.checkout.tip.for", { name: p.name })}
                  </span>
                  <input
                    inputMode="decimal"
                    value={tips[p.staffId] ?? ""}
                    onChange={(e) => {
                      setTips({ ...tips, [p.staffId]: e.target.value });
                      setPreset("other");
                      setCash(null);
                    }}
                    className={inputClass}
                  />
                </label>
              ))
            : null}
        </section>
      ) : null}

      {owe > 0 ? (
        bill.drawerOpen ? (
          <section className="flex flex-col gap-2">
            <label className="flex flex-col gap-1">
              <span className="bv-type-title2">{t("counter.checkout.cash")}</span>
              <input
                inputMode="decimal"
                value={cash ?? toBirr(owe, bill.currency)}
                onChange={(e) => setCash(e.target.value)}
                className={inputClass}
              />
            </label>
            <p role="status" className={`bv-type-body ${enough ? "text-fg" : "text-danger"}`}>
              {enough
                ? t("counter.checkout.change", { amount: money(handed - owe) })
                : t("counter.checkout.short", { amount: money(owe - handed) })}
            </p>
          </section>
        ) : (
          <section className="flex flex-col gap-3">
            <p role="status" className="bv-type-body">
              {t("counter.checkout.drawerClosed")}
            </p>
            <OpenForm
              language={language}
              orgId={orgId}
              locationId={bill.locationId}
              labels={{
                float: t("counter.checkout.float"),
                open: t("counter.checkout.openDrawer"),
              }}
            />
          </section>
        )
      ) : (
        <p className="bv-type-body text-fg-muted">{t("counter.checkout.nothingDue")}</p>
      )}

      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
          {error}
        </p>
      ) : null}

      <button
        type="button"
        disabled={pending || (owe > 0 && (!bill.drawerOpen || !enough))}
        onClick={take}
        className="bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle"
      >
        {owe > 0
          ? t("counter.checkout.take", { amount: money(owe) })
          : t("counter.checkout.complete")}
      </button>
    </div>
  );
}
