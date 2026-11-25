"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import type { Schemas } from "@beavent/api-client";
import { price } from "@beavent/venue";
import { useState, useTransition } from "react";
import { PhoneSignIn } from "@/components/customer/PhoneSignIn";
import { buyGiftCard } from "@/lib/customer/actions";

const input =
  "bv-type-body min-h-12 w-full rounded-lg border border-border-strong bg-background px-3 text-fg focus-visible:outline-2 focus-visible:outline-focus";
const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";

/** Birr as typed ("500", "1,000.50") to minor units, or null. */
function toMinor(value: string): number | null {
  const n = Number(value.replace(/,/g, "").trim());
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : null;
}

/**
 * The gift card form. The amount is a preset or another amount inside the business's limits; the recipient and the day
 * it is sent are optional (without a phone number the buyer gets the card themselves). Signing in comes last (DD10), then
 * the payment page.
 */
export function GiftCardBuy({
  language,
  locationId,
  business,
  offer,
  signedIn,
}: {
  language: Language;
  locationId: string;
  business: string;
  offer: Schemas["GiftCardOffer"];
  signedIn: boolean;
}) {
  const t = createTranslator(language);
  const currency = offer.currency ?? "ETB";
  const money = (minor: number) => price(minor, currency, language);
  const [preset, setPreset] = useState<number | "other">(offer.presetsMinor[0] ?? "other");
  const [other, setOther] = useState("");
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [message, setMessage] = useState("");
  const [sendOn, setSendOn] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const amount = preset === "other" ? toMinor(other) : preset;
  const inRange =
    amount !== null &&
    amount >= (offer.minMinor ?? 1) &&
    amount <= (offer.maxMinor ?? Number.MAX_SAFE_INTEGER);

  return (
    <form
      className="flex flex-col gap-6"
      onSubmit={(e) => {
        e.preventDefault();
        if (amount === null || !inRange) return;
        setError(null);
        start(async () => {
          const r = await buyGiftCard(language, {
            locationId,
            amountMinor: amount,
            recipientName: name,
            recipientPhone: phone,
            message,
            sendOn,
          });
          if (r.ok) window.location.assign(r.value.payUrl);
          else
            setError(
              r.code === "VALIDATION_FAILED"
                ? t("giftcards.buy.invalid")
                : r.message || t("booking.error"),
            );
        });
      }}
    >
      <p className="bv-type-body text-fg-muted">{t("giftcards.buy.intro", { business })}</p>

      <fieldset className="flex flex-col gap-3">
        <legend className="bv-type-title2 text-fg">{t("giftcards.buy.amount")}</legend>
        <div className="flex flex-wrap gap-3">
          {offer.presetsMinor.map((minor) => (
            <label
              key={minor}
              className={`bv-type-label inline-flex min-h-12 min-w-24 cursor-pointer items-center justify-center rounded-lg border px-4 has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-focus ${
                preset === minor
                  ? "border-primary bg-primary-subtle text-fg"
                  : "border-border-strong text-fg hover:bg-surface"
              }`}
            >
              <input
                type="radio"
                name="amount"
                className="sr-only"
                checked={preset === minor}
                onChange={() => setPreset(minor)}
              />
              {money(minor)}
            </label>
          ))}
          <label
            className={`bv-type-label inline-flex min-h-12 cursor-pointer items-center justify-center rounded-lg border px-4 has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-focus ${
              preset === "other"
                ? "border-primary bg-primary-subtle text-fg"
                : "border-border-strong text-fg hover:bg-surface"
            }`}
          >
            <input
              type="radio"
              name="amount"
              className="sr-only"
              checked={preset === "other"}
              onChange={() => setPreset("other")}
            />
            {t("giftcards.buy.other")}
          </label>
        </div>
        {preset === "other" ? (
          <label className="flex flex-col gap-2">
            <span className="bv-type-label text-fg">{t("giftcards.buy.other")}</span>
            <input
              inputMode="decimal"
              value={other}
              onChange={(e) => setOther(e.target.value)}
              aria-invalid={other !== "" && !inRange ? true : undefined}
              className={input}
            />
          </label>
        ) : null}
        {offer.minMinor != null && offer.maxMinor != null ? (
          <p className="bv-type-caption text-fg-muted">
            {t("giftcards.buy.range", { min: money(offer.minMinor), max: money(offer.maxMinor) })}
            {offer.validityMonths
              ? ` ${t("giftcards.buy.validity", { months: offer.validityMonths })}`
              : ""}
          </p>
        ) : null}
      </fieldset>

      <div className="flex flex-col gap-4">
        <label className="flex flex-col gap-2">
          <span className="bv-type-label text-fg">{t("giftcards.buy.recipientName")}</span>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={120}
            autoComplete="off"
            className={input}
          />
        </label>
        <label className="flex flex-col gap-2">
          <span className="bv-type-label text-fg">{t("giftcards.buy.recipientPhone")}</span>
          <input
            type="tel"
            inputMode="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            autoComplete="off"
            className={input}
          />
        </label>
        <label className="flex flex-col gap-2">
          <span className="bv-type-label text-fg">{t("giftcards.buy.message")}</span>
          <textarea
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            maxLength={500}
            rows={3}
            className={`${input} py-2`}
          />
        </label>
        <label className="flex flex-col gap-2">
          <span className="bv-type-label text-fg">{t("giftcards.buy.deliverAt")}</span>
          <input
            type="date"
            value={sendOn}
            onChange={(e) => setSendOn(e.target.value)}
            className={input}
          />
        </label>
      </div>

      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
          {error}
        </p>
      ) : null}

      {signedIn ? (
        <button type="submit" className={primary} disabled={pending || !inRange}>
          {t("giftcards.buy.pay")}
        </button>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="bv-type-body text-fg">{t("giftcards.buy.signIn")}</p>
          <PhoneSignIn language={language} />
        </div>
      )}
    </form>
  );
}
