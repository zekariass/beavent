"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { saveCustomerName, sendSignInCode, verifySignIn } from "@/lib/customer/actions";

const input =
  "bv-type-body min-h-12 w-full rounded-lg border border-border-strong bg-background px-3 text-fg focus-visible:outline-2 focus-visible:outline-focus";
const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";

/**
 * Inline sign-in by phone code (DD10): no separate screen, no password. Someone new then says what to call them (C-6
 * step 3). Refreshes the page when signed in.
 */
export function PhoneSignIn({ language }: { language: Language }) {
  const t = createTranslator(language);
  const router = useRouter();
  const [phone, setPhone] = useState("");
  const [challenge, setChallenge] = useState<{ id: string; phone: string } | null>(null);
  const [code, setCode] = useState("");
  const [askName, setAskName] = useState(false);
  const [given, setGiven] = useState("");
  const [father, setFather] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  return (
    <div className="flex max-w-md flex-col gap-3">
      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
          {error}
        </p>
      ) : null}
      {askName ? (
        <>
          <h2 className="bv-type-title2 text-fg">{t("signIn.name.title")}</h2>
          <label className="flex flex-col gap-2">
            <span className="bv-type-label text-fg">{t("signIn.name.given")}</span>
            <input
              value={given}
              onChange={(e) => setGiven(e.target.value)}
              autoComplete="given-name"
              maxLength={100}
              required
              className={input}
            />
          </label>
          <label className="flex flex-col gap-2">
            <span className="bv-type-label text-fg">{t("signIn.name.father")}</span>
            <input
              value={father}
              onChange={(e) => setFather(e.target.value)}
              autoComplete="family-name"
              maxLength={100}
              aria-describedby="sign-in-father-hint"
              className={input}
            />
            <span id="sign-in-father-hint" className="bv-type-caption text-fg-muted">
              {t("signIn.name.fatherHint")}
            </span>
          </label>
          <button
            type="button"
            className={primary}
            disabled={pending || !given.trim()}
            onClick={() =>
              start(async () => {
                const r = await saveCustomerName(language, given, father);
                if (r.ok) {
                  router.refresh();
                } else {
                  // Held for ten minutes: after that, start again with the phone number.
                  if (r.code === "UNAUTHENTICATED") {
                    setAskName(false);
                    setChallenge(null);
                    setCode("");
                  }
                  setError(r.message || t("booking.error"));
                }
              })
            }
          >
            {t("signIn.name.continue")}
          </button>
        </>
      ) : challenge ? (
        <>
          <label className="flex flex-col gap-2">
            <span className="bv-type-label text-fg">
              {t("booking.signIn.code", { phone: challenge.phone })}
            </span>
            <input
              value={code}
              onChange={(e) => setCode(e.target.value)}
              inputMode="numeric"
              autoComplete="one-time-code"
              className={input}
            />
          </label>
          <button
            type="button"
            className={primary}
            disabled={pending || code.trim().length < 4}
            onClick={() =>
              start(async () => {
                const r = await verifySignIn(language, challenge.id, code);
                if (r.ok && r.value.needsName) setAskName(true);
                else if (r.ok) router.refresh();
                else setError(r.message || t("booking.error"));
              })
            }
          >
            {t("nav.signIn")}
          </button>
        </>
      ) : (
        <>
          <p className="bv-type-body text-fg-muted">{t("booking.signIn.intro")}</p>
          <label className="flex flex-col gap-2">
            <span className="bv-type-label text-fg">{t("booking.signIn.phone")}</span>
            <input
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              inputMode="tel"
              autoComplete="tel"
              placeholder="09…"
              className={input}
            />
          </label>
          <button
            type="button"
            className={primary}
            disabled={pending || phone.trim().length < 9}
            onClick={() =>
              start(async () => {
                const r = await sendSignInCode(language, phone);
                if (r.ok) setChallenge({ id: r.value.challengeId, phone: r.value.phone });
                else setError(r.message || t("booking.error"));
              })
            }
          >
            {t("booking.signIn.send")}
          </button>
        </>
      )}
    </div>
  );
}
