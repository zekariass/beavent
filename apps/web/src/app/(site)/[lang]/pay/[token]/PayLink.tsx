"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import type { Schemas } from "@beavent/api-client";
import { price } from "@beavent/venue";
import { useCallback, useEffect, useRef, useState } from "react";
import { checkLinkPayment, startLinkPayment } from "@/lib/customer/actions";

const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";
const secondary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle";

/** How often and how long the page asks while the provider has not said yet (a payment can take a minute). */
const EVERY_MS = 3000;
const GIVE_UP_AFTER = 40;

type Dual = { primary: string; secondary: string };

/**
 * A payment link's page: what is being paid and for which booking, then the provider's page, then "checking your
 * payment" after the provider sends the payer back, and paid, declined, still waiting or no longer open. Everything
 * shown comes from Beavent's answer, never from the redirect.
 */
export function PayLink({
  language,
  token,
  outcome,
  initial,
  when,
  until,
}: {
  language: Language;
  token: string;
  outcome: "success" | "cancel" | "error" | "pending";
  initial: Schemas["PaymentLink"];
  when: { date: Dual; time: Dual } | null;
  until: string;
}) {
  const t = createTranslator(language);
  const [link, setLink] = useState(initial);
  const [asking, setAsking] = useState(outcome === "success" && initial.state === "OPEN");
  const [gaveUp, setGaveUp] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const tries = useRef(0);

  const ask = useCallback(async () => {
    const r = await checkLinkPayment(language, token);
    if (r.ok) setLink(r.value);
    return r.ok ? r.value : null;
  }, [language, token]);

  // A redirect that said "error" or "cancel" is not proof either: look once, so a decline reads as one.
  useEffect(() => {
    if (outcome === "success" || initial.state !== "OPEN") return;
    const timer = setTimeout(() => void ask(), 0);
    return () => clearTimeout(timer);
  }, [outcome, initial.state, ask]);

  useEffect(() => {
    if (!asking) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const loop = async () => {
      const next = await ask();
      if (stopped) return;
      tries.current += 1;
      if (next?.state !== "OPEN") setAsking(false);
      else if (tries.current >= GIVE_UP_AFTER) {
        setAsking(false);
        setGaveUp(true);
      } else timer = setTimeout(() => void loop(), EVERY_MS);
    };
    timer = setTimeout(() => void loop(), 800);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [asking, ask]);

  const pay = async () => {
    setBusy(true);
    setError(null);
    const r = await startLinkPayment(language, token);
    if (r.ok && r.value.redirectUrl) {
      window.location.assign(r.value.redirectUrl);
      return;
    }
    setBusy(false);
    setError(
      r.ok || r.code === "PAYMENT_UNAVAILABLE"
        ? t("booking.payment.unavailable")
        : r.message || t("booking.error"),
    );
  };

  const checkAgain = () => {
    tries.current = 0;
    setGaveUp(false);
    setAsking(true);
  };

  const balance = link.purpose === "BALANCE";
  const money = price(link.amountMinor, link.currency, language);
  const paid = link.state === "PAID";
  const closed = link.state === "EXPIRED";
  const failed = outcome === "error" && !paid && !closed;

  const title = paid
    ? balance
      ? t("pay.title.paid")
      : t("booking.done.booked")
    : closed
      ? t("pay.title.closed")
      : asking
        ? t("payment.title.checking")
        : failed
          ? t("payment.title.failed")
          : gaveUp
            ? t("payment.title.stillWaiting")
            : outcome === "cancel"
              ? t("payment.title.cancelled")
              : balance
                ? t("pay.title.balance")
                : t("payment.title.pending");
  const body = paid
    ? balance
      ? t("pay.body.paidBalance", { business: link.business })
      : t("pay.body.paidDeposit", { business: link.business })
    : closed
      ? t("pay.body.closed", { business: link.business })
      : asking
        ? t("payment.body.checking")
        : failed
          ? t("payment.body.failed")
          : gaveUp
            ? t("payment.body.stillWaiting")
            : outcome === "cancel"
              ? t("pay.body.cancelled")
              : null;

  return (
    <>
      <section className="flex flex-col gap-3">
        <h1 className="bv-type-title1 text-fg" role="status">
          {title}
        </h1>
        {body ? <p className="bv-type-body text-fg-muted">{body}</p> : null}
        {!paid && !closed && !asking && until ? (
          <p className="bv-type-body text-fg">{t("pay.openUntil", { time: until })}</p>
        ) : null}
        {error ? (
          <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
            {error}
          </p>
        ) : null}
      </section>

      <section className="flex flex-col gap-1 border-y border-border py-3">
        <span className="bv-type-body-strong text-fg">
          {[link.business, link.location].filter(Boolean).join(" · ")}
        </span>
        {link.items.length > 0 ? (
          <span className="bv-type-body text-fg">{link.items.join(", ")}</span>
        ) : null}
        {when ? (
          <>
            <span className="bv-type-body text-fg">
              {when.date.primary} · {when.time.primary}
            </span>
            <span className="bv-type-caption text-fg-muted">
              {when.date.secondary} · {when.time.secondary}
            </span>
          </>
        ) : null}
        {link.reference ? (
          <span className="bv-type-caption text-fg-muted">
            {t("appointment.reference", { reference: link.reference })}
          </span>
        ) : null}
      </section>

      <section className="flex justify-between gap-4">
        <span className="bv-type-body text-fg">
          {balance
            ? t("pay.balance")
            : link.purpose === "FULL"
              ? t("payment.full")
              : t("payment.deposit")}
        </span>
        <span className="bv-type-body-strong text-fg">
          {money}
          {paid ? ` · ${t("payment.paid")}` : ""}
        </span>
      </section>

      {!paid && !closed && !asking ? (
        <section className="flex flex-col gap-3">
          <button type="button" className={primary} disabled={busy} onClick={() => void pay()}>
            {failed
              ? t("payment.tryAgain", { amount: money })
              : t("payment.payNow", { amount: money })}
          </button>
          {gaveUp || outcome === "success" ? (
            <button type="button" className={secondary} disabled={busy} onClick={checkAgain}>
              {t("payment.checkAgain")}
            </button>
          ) : null}
        </section>
      ) : null}
    </>
  );
}
