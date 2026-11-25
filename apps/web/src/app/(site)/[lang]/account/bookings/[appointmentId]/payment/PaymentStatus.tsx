"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import type { Schemas } from "@beavent/api-client";
import { price } from "@beavent/venue";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { UseGiftCard } from "@/components/customer/UseGiftCard";
import {
  cancelAppointment,
  checkPayment,
  startPayment,
  tenderAppointment,
} from "@/lib/customer/actions";

const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";
const secondary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle";
const quiet =
  "bv-type-label inline-flex min-h-12 items-center self-start text-danger underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-focus";

/** How often and how long the page asks while the provider has not said yet (a payment can take a minute). */
const EVERY_MS = 3000;
const GIVE_UP_AFTER = 40;

type Dual = { primary: string; secondary: string };

/**
 * The payment page of a booking: "checking your payment" after the provider sends the person back, then paid, declined,
 * cancelled, still waiting or run out of time. Everything shown comes from Beavent's answer, never from the redirect.
 */
export function PaymentStatus({
  language,
  outcome,
  initial,
  when,
  venueHref,
  heldUntil,
  what,
}: {
  language: Language;
  outcome: "success" | "cancel" | "error" | "pending";
  initial: Schemas["AppointmentDetail"];
  when: { date: Dual; time: Dual };
  venueHref: string | null;
  heldUntil: string | null;
  /** Where and what: the business and the services, as booked. */
  what: string;
}) {
  const t = createTranslator(language);
  const [detail, setDetail] = useState(initial);
  // Asking runs while the provider's redirect said "paid" (or we do not know): other outcomes wait for the person.
  const [asking, setAsking] = useState(
    outcome === "success" && initial.status === "PENDING_PAYMENT",
  );
  const [gaveUp, setGaveUp] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const tries = useRef(0);

  const ask = useCallback(async () => {
    const r = await checkPayment(language, initial.id);
    if (r.ok) setDetail(r.value);
    return r.ok ? r.value : null;
  }, [language, initial.id]);

  // A redirect that said "error" or "cancel" is not proof either: look once, so a decline reads as one.
  useEffect(() => {
    if (outcome === "success" || initial.status !== "PENDING_PAYMENT") return;
    const timer = setTimeout(() => void ask(), 0);
    return () => clearTimeout(timer);
  }, [outcome, initial.status, ask]);

  useEffect(() => {
    if (!asking) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const loop = async () => {
      const next = await ask();
      if (stopped) return;
      tries.current += 1;
      const waiting =
        next?.status === "PENDING_PAYMENT" && next.payment?.state === "AWAITING_PAYMENT";
      if (!waiting) setAsking(false);
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
    const r = await startPayment(language, initial.id);
    if (r.ok && r.value.redirectUrl) {
      window.location.assign(r.value.redirectUrl);
      return;
    }
    setBusy(false);
    setError(
      r.ok
        ? t("booking.payment.unavailable")
        : r.code === "PAYMENT_UNAVAILABLE"
          ? t("booking.payment.unavailable")
          : r.message || t("booking.error"),
    );
  };

  const checkAgain = () => {
    tries.current = 0;
    setGaveUp(false);
    setAsking(true);
  };

  const spendBalance = async () => {
    setBusy(true);
    setError(null);
    const r = await tenderAppointment(language, initial.id, "BALANCE");
    if (r.ok) setDetail(r.value);
    else setError(r.code === "CONFLICT" ? t("giftcards.use.busy") : t("giftcards.use.failed"));
    setBusy(false);
  };

  const cancel = async () => {
    setBusy(true);
    const r = await cancelAppointment(language, initial.id);
    if (r.ok) setDetail(r.value);
    else setError(r.message || t("booking.error"));
    setBusy(false);
  };

  const status = detail.status;
  const payment = detail.payment;
  const money = (minor: number) => price(minor, payment?.currency ?? "ETB", language);
  const paid = status === "CONFIRMED" || status === "REQUESTED";
  const closed = status === "EXPIRED" || status === "CANCELLED" || payment?.state === "EXPIRED";
  const failed = payment?.state === "FAILED" || (outcome === "error" && !paid && !closed);
  const cancelled = status === "CANCELLED";

  const title = paid
    ? status === "REQUESTED"
      ? t("booking.done.requested")
      : t("booking.done.booked")
    : cancelled
      ? t("appointment.cancelled")
      : closed
        ? t("payment.title.expired")
        : asking
          ? t("payment.title.checking")
          : failed || outcome === "error"
            ? t("payment.title.failed")
            : gaveUp
              ? t("payment.title.stillWaiting")
              : outcome === "cancel"
                ? t("payment.title.cancelled")
                : t("payment.title.pending");
  const body =
    paid || cancelled
      ? null
      : closed
        ? t("payment.body.expired")
        : asking
          ? t("payment.body.checking")
          : failed || outcome === "error"
            ? t("payment.body.failed")
            : gaveUp
              ? t("payment.body.stillWaiting")
              : outcome === "cancel"
                ? t("payment.body.cancelled")
                : null;

  return (
    <>
      <section className="flex flex-col gap-3">
        <h1 className="bv-type-title1 text-fg" role="status">
          {title}
        </h1>
        {body ? <p className="bv-type-body text-fg-muted">{body}</p> : null}
        {!paid && !closed && heldUntil ? (
          <p className="bv-type-body text-fg">{t("payment.heldUntil", { time: heldUntil })}</p>
        ) : null}
        {error ? (
          <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
            {error}
          </p>
        ) : null}
      </section>

      <section className="flex flex-col gap-1 border-y border-border py-3">
        {what ? <span className="bv-type-body-strong text-fg">{what}</span> : null}
        <span className="bv-type-body text-fg">
          {when.date.primary} · {when.time.primary}
        </span>
        <span className="bv-type-caption text-fg-muted">
          {when.date.secondary} · {when.time.secondary}
        </span>
        <span className="bv-type-caption text-fg-muted">
          {t("appointment.reference", { reference: detail.reference })}
        </span>
      </section>

      {payment && payment.state !== "NOT_REQUIRED" ? (
        <section className="flex flex-col gap-2">
          <div className="flex justify-between gap-4">
            <span className="bv-type-body text-fg">
              {payment.state !== "PAID" && payment.paidMinor > 0
                ? t("payment.dueNow")
                : payment.atVenueMinor > 0
                  ? t("payment.deposit")
                  : t("payment.full")}
            </span>
            <span className="bv-type-body-strong text-fg">
              {money(payment.dueNowMinor)}
              {payment.state === "PAID" ? ` · ${t("payment.paid")}` : ""}
            </span>
          </div>
          {payment.state !== "PAID" && payment.paidMinor > 0 ? (
            <div className="flex justify-between gap-4">
              <span className="bv-type-body text-fg-muted">{t("payment.paid")}</span>
              <span className="bv-type-body text-fg-muted">{money(payment.paidMinor)}</span>
            </div>
          ) : null}
          {payment.atVenueMinor > 0 ? (
            <div className="flex justify-between gap-4">
              <span className="bv-type-body text-fg-muted">{t("payment.atVenue")}</span>
              <span className="bv-type-body text-fg-muted">{money(payment.atVenueMinor)}</span>
            </div>
          ) : null}
        </section>
      ) : null}

      <section className="flex flex-col gap-3">
        {paid ? (
          <Link href={`/${language}/account/bookings/${detail.id}`} className={primary}>
            {t("booking.done.viewBookings")}
          </Link>
        ) : closed ? (
          venueHref ? (
            <Link href={venueHref} className={primary}>
              {t("payment.chooseAnother")}
            </Link>
          ) : null
        ) : (
          <>
            {!asking ? (
              <button type="button" className={primary} disabled={busy} onClick={() => void pay()}>
                {failed
                  ? t("payment.tryAgain", { amount: money(payment?.dueNowMinor ?? 0) })
                  : t("payment.payNow", { amount: money(payment?.dueNowMinor ?? 0) })}
              </button>
            ) : null}
            {!asking && (gaveUp || outcome === "success") ? (
              <button type="button" className={secondary} disabled={busy} onClick={checkAgain}>
                {t("payment.checkAgain")}
              </button>
            ) : null}
            {!asking && payment && (payment.balanceMinor ?? 0) > 0 ? (
              <button
                type="button"
                className={secondary}
                disabled={busy}
                onClick={() => void spendBalance()}
              >
                {t("giftcards.use.balance", {
                  amount: money(Math.min(payment.balanceMinor ?? 0, payment.dueNowMinor)),
                })}
              </button>
            ) : null}
            {!asking ? (
              <UseGiftCard
                language={language}
                disabled={busy}
                apply={async (code) => {
                  const r = await tenderAppointment(language, initial.id, "GIFT_CARD", code);
                  if (r.ok) setDetail(r.value);
                  return r.ok ? { ok: true } : r;
                }}
              />
            ) : null}
            {!asking ? (
              <button type="button" className={quiet} disabled={busy} onClick={() => void cancel()}>
                {t("appointment.cancel")}
              </button>
            ) : null}
          </>
        )}
      </section>
    </>
  );
}
