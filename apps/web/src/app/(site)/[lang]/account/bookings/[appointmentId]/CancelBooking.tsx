"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { cancelAppointment } from "@/lib/customer/actions";

/** Cancel (S-13): states what cancelling now costs before asking; the page updates in place afterwards. */
export function CancelBooking({
  language,
  appointmentId,
  request,
  feeText,
  creditWith,
}: {
  language: Language;
  appointmentId: string;
  request: boolean;
  feeText: string;
  /** The business's name when something was paid, so the refund can be kept as credit with it; else null. */
  creditWith: string | null;
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refundTo, setRefundTo] = useState<"ORIGINAL" | "BALANCE">("ORIGINAL");
  const [pending, start] = useTransition();
  if (!asking) {
    return (
      <button
        type="button"
        onClick={() => setAsking(true)}
        className="bv-type-label inline-flex min-h-12 items-center self-start text-danger underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-focus"
      >
        {request ? t("appointment.cancelRequest") : t("appointment.cancel")}
      </button>
    );
  }
  return (
    <div
      role="dialog"
      aria-labelledby="cancel-title"
      className="flex flex-col gap-3 rounded-lg border border-border p-4"
    >
      <h2 id="cancel-title" className="bv-type-title2 text-fg">
        {t("appointment.cancelTitle")}
      </h2>
      <p className="bv-type-body text-fg">{feeText}</p>
      {creditWith ? (
        <fieldset className="flex flex-col gap-1">
          {(["ORIGINAL", "BALANCE"] as const).map((value) => (
            <label key={value} className="flex min-h-12 items-center gap-3">
              <input
                type="radio"
                name="refundTo"
                value={value}
                checked={refundTo === value}
                onChange={() => setRefundTo(value)}
                className="size-5 accent-primary"
              />
              <span className="bv-type-body text-fg">
                {value === "ORIGINAL"
                  ? t("appointment.refundTo.original")
                  : t("appointment.refundTo.balance", { business: creditWith })}
              </span>
            </label>
          ))}
        </fieldset>
      ) : null}
      {error ? (
        <p role="alert" className="bv-type-body text-fg">
          {error}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-3">
        <button
          type="button"
          disabled={pending}
          onClick={() =>
            start(async () => {
              const r = await cancelAppointment(
                language,
                appointmentId,
                creditWith ? refundTo : undefined,
              );
              if (r.ok) {
                setAsking(false);
                router.refresh();
              } else setError(r.message || t("booking.error"));
            })
          }
          className="bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-danger px-5 text-on-danger focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
        >
          {request ? t("appointment.cancelRequest") : t("appointment.cancel")}
        </button>
        <button
          type="button"
          onClick={() => setAsking(false)}
          className="bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface"
        >
          {t("appointment.keep")}
        </button>
      </div>
    </div>
  );
}
