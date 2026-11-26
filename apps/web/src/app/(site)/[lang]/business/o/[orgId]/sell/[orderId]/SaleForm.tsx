"use client";

import type { Schemas } from "@beavent/api-client";
import { createTranslator, type Language, type MessageKey } from "@beavent/i18n";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { formatPrice, inputClass, toBirr } from "@/components/business/ui";
import { OpenForm } from "../../cash/CashForms";
import { cancelSale, paySale } from "../actions";

const secondary =
  "bv-type-label inline-flex min-h-11 shrink-0 items-center justify-center whitespace-nowrap rounded-lg border border-border-strong px-4 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle";
const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";

function minor(value: string): number {
  const n = Number(value.replace(/,/g, "").trim());
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : 0;
}

/** The payment for a walk-in sale: gift cards, the customer's credit, the cash and the change, then the receipt. */
export function SaleForm({
  language,
  orgId,
  sale,
}: {
  language: Language;
  orgId: string;
  sale: Schemas["SaleBill"];
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const base = `/${language}/business/o/${orgId}`;
  const money = (amount: number) => formatPrice(amount, sale.currency, language);
  const methodLabel = (m: string) =>
    t(`counter.checkout.method.${m}` as MessageKey) === `counter.checkout.method.${m}`
      ? m
      : t(`counter.checkout.method.${m}` as MessageKey);
  const [cards, setCards] = useState<string[]>([]);
  const [cardInput, setCardInput] = useState("");
  const [useBalance, setUseBalance] = useState(false);
  const [cash, setCash] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Schemas["CheckoutDone"] | null>(null);
  const [cancelled, setCancelled] = useState(false);
  const [pending, start] = useTransition();

  const owe = sale.dueMinor;
  const valueUsed = cards.length > 0 || useBalance;
  const handed = cash === null || cash.trim() === "" ? owe : minor(cash);
  const enough = valueUsed || handed >= owe;

  if (cancelled) {
    return (
      <div className="flex flex-col gap-4">
        <p role="status" className="bv-type-body">
          {t("sell.cancelled")}
        </p>
        <Link href={`${base}/sell`} className={`${secondary} self-start`}>
          {t("sell.new")}
        </Link>
      </div>
    );
  }

  if (done || sale.receiptNumber) {
    const receipt = done?.receiptNumber ?? sale.receiptNumber ?? "";
    return (
      <section className="flex max-w-xl flex-col gap-4">
        <p role="status" className="bv-type-title2">
          {t("counter.checkout.done")} · {t("counter.checkout.receipt", { number: receipt })}
        </p>
        {done && done.changeMinor > 0 ? (
          <p className="bv-type-title2 text-fg">
            {t("counter.checkout.giveChange", { amount: money(done.changeMinor) })}
          </p>
        ) : null}
        {done ? (
          <ul className="divide-y divide-border border-y border-border">
            <li className="flex justify-between gap-4 py-3">
              <span className="bv-type-body">{t("counter.checkout.total")}</span>
              <span className="bv-type-body tabular-nums">{money(done.totalMinor)}</span>
            </li>
            {done.tenders.map((x, i) => (
              <li key={i} className="flex justify-between gap-4 py-3">
                <span className="bv-type-body">{methodLabel(x.method)}</span>
                <span className="bv-type-body tabular-nums">{money(x.amountMinor)}</span>
              </li>
            ))}
          </ul>
        ) : null}
        <Link href={`${base}/receipts/${sale.orderId}`} className={primary}>
          {t("receipt.open")}
        </Link>
        <Link
          href={`${base}/sell`}
          className="bv-type-label inline-flex min-h-11 items-center self-start underline underline-offset-4"
        >
          {t("sell.new")}
        </Link>
      </section>
    );
  }

  const take = () =>
    start(async () => {
      setError(null);
      const r = await paySale(language, orgId, sale.orderId, {
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

  const cancel = () =>
    start(async () => {
      setError(null);
      const r = await cancelSale(language, orgId, sale.orderId);
      if (r.ok) setCancelled(true);
      else setError(r.message || t("counter.checkout.error"));
    });

  return (
    <div className="flex max-w-xl flex-col gap-6">
      <p className="bv-type-body text-fg-muted">{t("sell.sale", { number: sale.number })}</p>

      <section className="flex flex-col gap-2">
        <h2 className="bv-type-title2">{t("counter.checkout.bill")}</h2>
        <ul className="divide-y divide-border border-y border-border">
          {sale.lines.map((l, i) => (
            <li key={i} className="flex min-h-12 items-center justify-between gap-4 py-2">
              <span className="bv-type-body">{l.description}</span>
              <span className="bv-type-body tabular-nums">{money(l.totalMinor)}</span>
            </li>
          ))}
          {sale.tenders.map((x, i) => (
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
            <span className="bv-type-body font-medium tabular-nums">{money(owe)}</span>
          </li>
        </ul>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="bv-type-title2">{t("counter.checkout.pay")}</h2>
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
        {sale.balanceMinor > 0 ? (
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
              {t("counter.checkout.credit", { amount: money(sale.balanceMinor) })}
            </span>
          </label>
        ) : null}
        {valueUsed ? (
          <p className="bv-type-caption text-fg-muted">{t("counter.checkout.valueFirst")}</p>
        ) : null}
      </section>

      {owe > 0 ? (
        sale.drawerOpen || valueUsed ? (
          <section className="flex flex-col gap-2">
            <label className="flex flex-col gap-1">
              <span className="bv-type-title2">{t("counter.checkout.cash")}</span>
              <input
                inputMode="decimal"
                value={cash ?? (valueUsed ? "" : toBirr(owe, sale.currency))}
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
              locationId={sale.locationId}
              labels={{
                float: t("counter.checkout.float"),
                open: t("counter.checkout.openDrawer"),
              }}
            />
          </section>
        )
      ) : null}

      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
          {error}
        </p>
      ) : null}

      <button
        type="button"
        disabled={pending || (owe > 0 && !enough) || (owe > 0 && !valueUsed && !sale.drawerOpen)}
        onClick={take}
        className={primary}
      >
        {owe > 0 && !valueUsed
          ? t("counter.checkout.take", { amount: money(owe) })
          : t("counter.checkout.complete")}
      </button>
      {sale.canCancel ? (
        <button
          type="button"
          disabled={pending}
          onClick={cancel}
          className={`${secondary} self-start`}
        >
          {t("sell.cancel")}
        </button>
      ) : null}
    </div>
  );
}
