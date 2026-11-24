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
}: {
  language: Language;
  appointmentId: string;
  request: boolean;
  feeText: string;
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
              const r = await cancelAppointment(language, appointmentId);
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
