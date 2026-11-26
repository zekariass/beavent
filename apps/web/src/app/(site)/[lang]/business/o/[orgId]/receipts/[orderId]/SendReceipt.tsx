"use client";

import { createTranslator, type Language, type MessageKey } from "@beavent/i18n";
import { useState, useTransition } from "react";
import { sendReceipt } from "../../checkout/actions";

/** Sends the customer a link to their receipt by text message (or email), and says where it went. */
export function SendReceipt({
  language,
  orgId,
  orderId,
}: {
  language: Language;
  orgId: string;
  orderId: string;
}) {
  const t = createTranslator(language);
  const [via, setVia] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  return (
    <div className="flex flex-col gap-2">
      <button
        type="button"
        disabled={pending}
        onClick={() =>
          start(async () => {
            setError(null);
            const r = await sendReceipt(language, orgId, orderId);
            if (r.ok) setVia(r.via);
            else setError(r.code === "CONFLICT" ? t("receipt.noContact") : r.message);
          })
        }
        className="bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle"
      >
        {t("receipt.send")}
      </button>
      {via ? (
        <p role="status" className="bv-type-body text-fg-muted">
          {t(`receipt.sent.${via}` as MessageKey)}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
          {error}
        </p>
      ) : null}
    </div>
  );
}
