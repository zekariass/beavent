"use client";

import type { Schemas } from "@beavent/api-client";
import { createTranslator, type Language, type MessageKey } from "@beavent/i18n";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState, useTransition } from "react";
import { formatPrice, inputClass, toBirr } from "@/components/business/ui";
import { sendPaymentLink } from "../../calendar/actions";
import { OpenForm } from "../../cash/CashForms";
import { checkoutVisit } from "../actions";

const PRESETS = [5, 10, 15] as const;
const secondary =
  "bv-type-label inline-flex min-h-11 shrink-0 items-center justify-center whitespace-nowrap rounded-lg border border-border-strong px-4 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle";

/** Birr as typed to minor units; 0 for an empty or unreadable field. */
function minor(value: string): number {
  const n = Number(value.replace(/,/g, "").trim());
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : 0;
}

/**
 * The counter: what the visit comes to, a discount, a tip split between the people who worked on it, gift cards and the
 * customer's credit, the cash for the rest and the change. One primary action completes the sale and issues the receipt.
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
  const router = useRouter();
  const money = (amount: number) => formatPrice(amount, bill.currency, language);
  const [preset, setPreset] = useState<number | "none" | "other">("none");
  const [tips, setTips] = useState<Record<string, string>>({});
  const [discountOn, setDiscountOn] = useState(false);
  const [discountKind, setDiscountKind] = useState<"PERCENT" | "AMOUNT">("PERCENT");
  const [discountValue, setDiscountValue] = useState("");
  const [discountReason, setDiscountReason] = useState("");
  const [cards, setCards] = useState<string[]>([]);
  const [cardInput, setCardInput] = useState("");
  const [useBalance, setUseBalance] = useState(false);
  const [cash, setCash] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Schemas["CheckoutDone"] | null>(null);
  const [linkVia, setLinkVia] = useState<"SMS" | "EMAIL" | null>(null);
  const [pending, start] = useTransition();
  const base = `/${language}/business/o/${orgId}`;

  // A link asked for: look again for the payment every few seconds.
  useEffect(() => {
    if (!linkVia || done || bill.dueMinor === 0) return;
    const timer = setInterval(() => router.refresh(), 4000);
    return () => clearInterval(timer);
  }, [linkVia, done, bill.dueMinor, router]);

  const discountMinor = useMemo(() => {
    if (!discountOn || !bill.canDiscount) return 0;
    const v = discountKind === "PERCENT" ? Number(discountValue) : minor(discountValue);
    if (!Number.isFinite(v) || v <= 0) return 0;
    const raw =
      discountKind === "PERCENT" ? Math.round((bill.totalMinor * Math.min(v, 100)) / 100) : v;
    return Math.min(raw, bill.totalMinor);
  }, [discountOn, discountKind, discountValue, bill.canDiscount, bill.totalMinor]);
  const tipBase = bill.totalMinor - discountMinor;
  const tipTotal = useMemo(
    () => bill.team.reduce((sum, p) => sum + minor(tips[p.staffId] ?? ""), 0),
    [bill.team, tips],
  );
  const owe = bill.dueMinor - discountMinor + tipTotal;
  const valueUsed = cards.length > 0 || useBalance;
  const handed = cash === null || cash.trim() === "" ? owe : minor(cash);
  const enough = valueUsed || handed >= owe;
  const methodLabel = (m: string) =>
    t(`counter.checkout.method.${m}` as MessageKey) === `counter.checkout.method.${m}`
      ? m
      : t(`counter.checkout.method.${m}` as MessageKey);

  /** A tip as a share of the visit, split evenly between the team (the first person gets the odd coin). */
  const choose = (next: number | "none" | "other") => {
    setPreset(next);
    if (next === "other") return;
    const total = next === "none" ? 0 : Math.round((tipBase * next) / 100);
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
          {done.discountMinor > 0 ? (
            <li className="flex justify-between gap-4 py-3">
              <span className="bv-type-body">{t("counter.checkout.discount.row")}</span>
              <span className="bv-type-body tabular-nums">−{money(done.discountMinor)}</span>
            </li>
          ) : null}
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
          {done.tenders.map((x, i) => (
            <li key={i} className="flex justify-between gap-4 py-3">
              <span className="bv-type-body">{methodLabel(x.method)}</span>
              <span className="bv-type-body tabular-nums">{money(x.amountMinor)}</span>
            </li>
          ))}
        </ul>
        <Link
          href={`${base}/receipts/${done.orderId}`}
          className="bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
        >
          {t("receipt.open")}
        </Link>
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
      <div className="flex flex-col gap-3">
        <p role="status" className="bv-type-body">
          {t("counter.checkout.alreadyDone", { number: bill.receiptNumber })}
        </p>
        {bill.orderId ? (
          <Link
            href={`${base}/receipts/${bill.orderId}`}
            className="bv-type-label inline-flex min-h-12 items-center justify-center self-start rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
          >
            {t("receipt.open")}
          </Link>
        ) : null}
      </div>
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
      const r = await checkoutVisit(language, orgId, bill.appointmentId, {
        tips: bill.team.map((p) => ({
          staffId: p.staffId,
          amountMinor: minor(tips[p.staffId] ?? ""),
        })),
        discount:
          discountMinor > 0
            ? {
                kind: discountKind,
                value: discountKind === "PERCENT" ? Number(discountValue) : minor(discountValue),
                reason: discountReason.trim(),
              }
            : null,
        giftCards: cards,
        useBalance,
        cashTenderedMinor:
          owe > 0 && !valueUsed ? handed : cash && cash.trim() ? minor(cash) : null,
      });
      if (r.ok) setDone(r.done);
      else {
        setError(
          r.code === "NOT_FOUND" && cards.length > 0
            ? t("counter.checkout.error.card")
            : r.message || t("counter.checkout.error"),
        );
        router.refresh(); // what was paid before it failed shows on the bill
      }
    });

  const askByText = () =>
    start(async () => {
      setError(null);
      const r = await sendPaymentLink(language, orgId, bill.appointmentId);
      if (r.ok) setLinkVia(r.sentTo);
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
          {discountMinor > 0 ? (
            <li className="flex min-h-12 items-center justify-between gap-4 py-2">
              <span className="bv-type-body">{t("counter.checkout.discount.row")}</span>
              <span className="bv-type-body tabular-nums">−{money(discountMinor)}</span>
            </li>
          ) : null}
          {bill.paidMinor > 0 ? (
            <li className="flex min-h-12 items-center justify-between gap-4 py-2">
              <span className="bv-type-body text-fg-muted">{t("counter.checkout.paidOnline")}</span>
              <span className="bv-type-body tabular-nums text-fg-muted">
                −{money(bill.paidMinor)}
              </span>
            </li>
          ) : null}
          {bill.tenders.map((x, i) => (
            <li key={`t${i}`} className="flex min-h-12 items-center justify-between gap-4 py-2">
              <span className="bv-type-body text-fg-muted">
                {t("counter.checkout.paidSoFar")} · {methodLabel(x.method)}
              </span>
              <span className="bv-type-body tabular-nums text-fg-muted">
                −{money(x.amountMinor)}
              </span>
            </li>
          ))}
          <li className="flex min-h-12 items-center justify-between gap-4 py-2">
            <span className="bv-type-body font-medium">{t("counter.checkout.due")}</span>
            <span className="bv-type-body font-medium tabular-nums">
              {money(bill.dueMinor - discountMinor)}
            </span>
          </li>
        </ul>
      </section>

      {bill.canDiscount ? (
        <section className="flex flex-col gap-3">
          <h2 className="bv-type-title2">{t("counter.checkout.discount")}</h2>
          <div
            role="group"
            aria-label={t("counter.checkout.discount")}
            className="flex flex-wrap gap-3"
          >
            {([false, true] as const).map((on) => (
              <button
                key={String(on)}
                type="button"
                aria-pressed={discountOn === on}
                onClick={() => {
                  setDiscountOn(on);
                  setCash(null);
                }}
                className={`bv-type-label inline-flex min-h-11 items-center justify-center rounded-lg border px-4 focus-visible:outline-2 focus-visible:outline-focus ${
                  discountOn === on
                    ? "border-primary bg-primary-subtle text-fg"
                    : "border-border-strong text-fg hover:bg-surface"
                }`}
              >
                {on ? t("counter.checkout.discount") : t("counter.checkout.discount.none")}
              </button>
            ))}
          </div>
          {discountOn ? (
            <>
              <div className="flex flex-col gap-1">
                <span className="bv-type-label">{t("counter.checkout.discount.value")}</span>
                <div className="flex gap-3">
                  <select
                    aria-label={t("counter.checkout.discount.kind")}
                    value={discountKind}
                    onChange={(e) => setDiscountKind(e.target.value as "PERCENT" | "AMOUNT")}
                    className={`${inputClass} max-w-44`}
                  >
                    <option value="PERCENT">{t("counter.checkout.discount.PERCENT")}</option>
                    <option value="AMOUNT">{t("counter.checkout.discount.AMOUNT")}</option>
                  </select>
                  <input
                    aria-label={t("counter.checkout.discount.value")}
                    inputMode="decimal"
                    value={discountValue}
                    onChange={(e) => {
                      setDiscountValue(e.target.value);
                      setCash(null);
                    }}
                    className={inputClass}
                  />
                </div>
              </div>
              <label className="flex flex-col gap-1">
                <span className="bv-type-label">{t("counter.checkout.discount.reason")}</span>
                <input
                  value={discountReason}
                  onChange={(e) => setDiscountReason(e.target.value)}
                  maxLength={200}
                  className={inputClass}
                />
              </label>
            </>
          ) : null}
        </section>
      ) : null}

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

      {bill.dueMinor > 0 ? (
        <section className="flex flex-col gap-3">
          <h2 className="bv-type-title2">{t("counter.checkout.pay")}</h2>
          {bill.paidMinor > 0 ? (
            <div className="flex flex-col gap-2">
              <button
                type="button"
                disabled={pending}
                onClick={askByText}
                className={`${secondary} self-start`}
              >
                {t("counter.checkout.link.send")}
              </button>
              {linkVia ? (
                <p role="status" className="bv-type-body text-fg-muted">
                  {t("counter.checkout.link.waiting", {
                    via: t(`counter.checkout.link.via.${linkVia}` as MessageKey),
                  })}
                </p>
              ) : null}
            </div>
          ) : null}
          <form
            className="flex flex-col gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              const code = cardInput.trim();
              if (code && !cards.includes(code)) setCards([...cards, code]);
              setCardInput("");
            }}
          >
            <label className="flex flex-col gap-1">
              <span className="bv-type-label">{t("counter.checkout.card")}</span>
              <div className="flex gap-3">
                <input
                  name="giftCardCode"
                  value={cardInput}
                  onChange={(e) => setCardInput(e.target.value)}
                  autoComplete="off"
                  autoCapitalize="characters"
                  spellCheck={false}
                  maxLength={40}
                  className={inputClass}
                />
                <button type="submit" disabled={!cardInput.trim()} className={secondary}>
                  {t("counter.checkout.card.add")}
                </button>
              </div>
            </label>
          </form>
          {cards.map((code) => (
            <div key={code} className="flex items-center justify-between gap-3">
              <span className="bv-type-body">
                {t("counter.checkout.method.GIFT_CARD")} · {code}
              </span>
              <button
                type="button"
                aria-label={t("counter.checkout.card.remove", { code })}
                onClick={() => setCards(cards.filter((c) => c !== code))}
                className="bv-type-label inline-flex min-h-11 items-center px-2 text-fg-muted underline underline-offset-4"
              >
                ×
              </button>
            </div>
          ))}
          {bill.balanceMinor > 0 ? (
            <label className="flex min-h-11 items-center gap-3">
              <input
                type="checkbox"
                checked={useBalance}
                onChange={(e) => {
                  setUseBalance(e.target.checked);
                  setCash(null);
                }}
                className="size-5 accent-primary"
              />
              <span className="bv-type-body">
                {t("counter.checkout.credit", { amount: money(bill.balanceMinor) })}
              </span>
            </label>
          ) : null}
          {valueUsed ? (
            <p className="bv-type-caption text-fg-muted">{t("counter.checkout.valueFirst")}</p>
          ) : null}
        </section>
      ) : null}

      {owe > 0 ? (
        bill.drawerOpen || valueUsed ? (
          <section className="flex flex-col gap-2">
            <label className="flex flex-col gap-1">
              <span className="bv-type-title2">{t("counter.checkout.cash")}</span>
              <input
                inputMode="decimal"
                value={cash ?? (valueUsed ? "" : toBirr(owe, bill.currency))}
                onChange={(e) => setCash(e.target.value)}
                className={inputClass}
              />
            </label>
            {!valueUsed ? (
              <p role="status" className={`bv-type-body ${enough ? "text-fg" : "text-danger"}`}>
                {enough
                  ? t("counter.checkout.change", { amount: money(handed - owe) })
                  : t("counter.checkout.short", { amount: money(owe - handed) })}
              </p>
            ) : null}
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
        disabled={
          pending ||
          (owe > 0 && !enough) ||
          (owe > 0 && !valueUsed && !bill.drawerOpen) ||
          (discountOn && discountMinor > 0 && discountReason.trim().length < 3)
        }
        onClick={take}
        className="bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle"
      >
        {owe > 0 && !valueUsed
          ? t("counter.checkout.take", { amount: money(owe) })
          : t("counter.checkout.complete")}
      </button>
    </div>
  );
}
