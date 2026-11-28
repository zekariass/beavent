"use client";

import { useState, useTransition } from "react";
import type { Language } from "@beavent/i18n";
import { stopMessages } from "@/lib/customer/stop-actions";
import { translator } from "@/lib/i18n";

/** The button on the stop page: one primary action, and a line that says it worked (or that it did not). */
export function StopForm({
  language,
  token,
  business,
}: {
  language: Language;
  token: string;
  business: string;
}) {
  const t = translator(language);
  const [state, setState] = useState<"idle" | "done" | "error">("idle");
  const [pending, start] = useTransition();
  if (state === "done")
    return (
      <p role="status" className="bv-type-body text-success">
        {t("stop.done", { business })}
      </p>
    );
  return (
    <div className="flex flex-col items-start gap-3">
      <button
        type="button"
        disabled={pending}
        onClick={() =>
          start(async () => setState((await stopMessages(language, token)) ? "done" : "error"))
        }
        className="bv-type-button inline-flex min-h-12 items-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus disabled:opacity-50"
      >
        {t("stop.button")}
      </button>
      {state === "error" ? (
        <p role="alert" className="bv-type-label text-danger">
          {t("stop.error")}
        </p>
      ) : null}
    </div>
  );
}
