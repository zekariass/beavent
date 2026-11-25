"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { acceptWaitlistOffer, answerWaitlist } from "@/lib/customer/actions";

const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";
const quiet =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg px-4 text-fg-muted hover:text-fg focus-visible:outline-2 focus-visible:outline-focus";

/** Book this time / Not this time for an offer; Leave waitlist otherwise. */
export function WaitlistActions({
  language,
  entryId,
  offered,
}: {
  language: Language;
  entryId: string;
  offered: boolean;
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const [pending, start] = useTransition();
  const [message, setMessage] = useState<string | null>(null);
  return (
    <section className="flex flex-col gap-3">
      {message ? (
        <p role="status" className="bv-type-body rounded-lg bg-surface px-4 py-3 text-fg">
          {message}
        </p>
      ) : null}
      {offered ? (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            disabled={pending}
            className={primary}
            onClick={() =>
              start(async () => {
                const r = await acceptWaitlistOffer(language, entryId);
                if (r.ok) router.push(`/${language}/account/bookings/${r.value.appointmentId}`);
                else if (r.code === "HOLD_EXPIRED") {
                  setMessage(t("waitlist.expired"));
                  router.refresh();
                } else setMessage(r.message || t("booking.error"));
              })
            }
          >
            {t("waitlist.book")}
          </button>
          <button
            type="button"
            disabled={pending}
            className={quiet}
            onClick={() =>
              start(async () => {
                const r = await answerWaitlist(language, entryId, false);
                if (r.ok) router.refresh();
                else setMessage(r.message || t("booking.error"));
              })
            }
          >
            {t("waitlist.notThisTime")}
          </button>
        </div>
      ) : null}
      <button
        type="button"
        disabled={pending}
        className={`${quiet} self-start text-danger hover:text-danger`}
        onClick={() =>
          start(async () => {
            const r = await answerWaitlist(language, entryId, true);
            if (r.ok) router.push(`/${language}/account/bookings`);
            else setMessage(r.message || t("booking.error"));
          })
        }
      >
        {t("waitlist.leave")}
      </button>
    </section>
  );
}
