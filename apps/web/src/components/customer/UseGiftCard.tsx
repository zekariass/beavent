"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import { useState, useTransition } from "react";

const input =
  "bv-type-body min-h-12 w-full rounded-lg border border-border-strong bg-background px-3 text-fg focus-visible:outline-2 focus-visible:outline-focus";
const secondary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle";

/**
 * "Use a gift card" on a payment page (plan 3.8): a code field that asks Beavent to take what the card can cover off
 * what is due. The card is never described when it fails: an unknown, spent, expired or another business's card all read
 * the same. {@link apply} returns whether it worked; the page updates itself from Beavent's answer.
 */
export function UseGiftCard({
  language,
  apply,
  disabled,
}: {
  language: Language;
  apply: (code: string) => Promise<{ ok: true } | { ok: false; code?: string; message: string }>;
  disabled?: boolean;
}) {
  const t = createTranslator(language);
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  if (!open) {
    return (
      <button
        type="button"
        className="bv-type-label inline-flex min-h-12 items-center self-start text-fg underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle"
        disabled={disabled}
        onClick={() => setOpen(true)}
      >
        {t("giftcards.use.title")}
      </button>
    );
  }
  return (
    <form
      className="flex flex-col gap-3"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        start(async () => {
          const r = await apply(code.trim());
          if (r.ok) {
            setCode("");
            setOpen(false);
          } else {
            setError(r.code === "CONFLICT" ? t("giftcards.use.busy") : t("giftcards.use.failed"));
          }
        });
      }}
    >
      <label className="flex flex-col gap-2">
        <span className="bv-type-label text-fg">{t("giftcards.use.code")}</span>
        <input
          name="giftCardCode"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          maxLength={40}
          required
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? "gift-card-error" : undefined}
          className={input}
        />
      </label>
      {error ? (
        <p
          id="gift-card-error"
          role="alert"
          className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg"
        >
          {error}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-3">
        <button type="submit" className={secondary} disabled={pending || code.trim() === ""}>
          {t("giftcards.use.apply")}
        </button>
        <button
          type="button"
          className="bv-type-label inline-flex min-h-12 items-center px-2 text-fg-muted underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-focus"
          onClick={() => {
            setOpen(false);
            setError(null);
          }}
        >
          {t("giftcards.use.dismiss")}
        </button>
      </div>
    </form>
  );
}
