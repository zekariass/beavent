"use client";

import type { Schemas } from "@beavent/api-client";
import { createTranslator, localized, type Language } from "@beavent/i18n";
import Link from "next/link";
import { useState, useTransition } from "react";
import { answerForm } from "./actions";

const input =
  "min-h-12 w-full rounded-lg border border-border-strong bg-background px-3 py-2 text-base text-fg outline-none focus:border-primary focus:ring-1 focus:ring-primary";
const choice =
  "flex min-h-12 cursor-pointer items-center gap-3 rounded-lg border border-border-strong px-3 py-2 has-[:checked]:border-primary has-[:checked]:bg-primary-subtle";
const primary =
  "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle";

type Answers = Record<string, unknown>;

/** Whether a question still needs an answer (required and empty, or an agreement not ticked). */
const missing = (f: Schemas["FormField"], v: unknown) =>
  f.required &&
  (v === undefined ||
    v === null ||
    (typeof v === "string" && v.trim() === "") ||
    (Array.isArray(v) && v.length === 0) ||
    (f.kind === "CHECKBOX" && v !== true));

/** Renders the business's questions in the reader's language and sends the answers. */
export function FormFill({
  language,
  appointmentId,
  form,
  business,
  back,
}: {
  language: Language;
  appointmentId: string;
  form: Schemas["MyFormQuestions"];
  business: string;
  back: string;
}) {
  const t = createTranslator(language);
  const [answers, setAnswers] = useState<Answers>({});
  const [signature, setSignature] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const [pending, start] = useTransition();
  const set = (id: string, value: unknown) => setAnswers((a) => ({ ...a, [id]: value }));

  if (sent)
    return (
      <div className="flex flex-col gap-4">
        <p role="status" className="bv-type-body rounded-lg bg-success-subtle px-4 py-3 text-fg">
          {t("form.sent", { name: business })}
        </p>
        <Link href={back} className={`${primary} self-start`}>
          {t("form.back")}
        </Link>
      </div>
    );

  function send() {
    if (
      form.fields.some((f) => missing(f, answers[f.id])) ||
      (form.signature && !signature.trim())
    ) {
      setError(t("form.missing"));
      return;
    }
    setError(null);
    start(async () => {
      const result = await answerForm(
        language,
        appointmentId,
        form.formId,
        answers,
        form.signature ? signature.trim() : null,
      );
      if (result.ok) setSent(true);
      else setError(result.message || t("error.generic"));
    });
  }

  return (
    <form
      className="flex flex-col gap-6"
      onSubmit={(e) => {
        e.preventDefault();
        send();
      }}
      noValidate
    >
      {form.fields.map((f) => {
        const label = localized(f.label, language);
        const help = f.help && Object.keys(f.help).length > 0 ? localized(f.help, language) : null;
        const value = answers[f.id];
        const heading = (
          <span className="flex flex-col gap-1">
            <span className="bv-type-label text-fg">{label}</span>
            {!f.required ? (
              <span className="bv-type-caption text-fg-muted">{t("form.optional")}</span>
            ) : null}
            {help ? <span className="bv-type-caption text-fg-muted">{help}</span> : null}
          </span>
        );
        if (f.kind === "CHECKBOX")
          return (
            <label key={f.id} className={choice}>
              <input
                type="checkbox"
                checked={value === true}
                onChange={(e) => set(f.id, e.target.checked)}
                className="size-5 shrink-0 accent-primary"
              />
              <span className="flex flex-col gap-1">
                <span className="bv-type-body text-fg">{label}</span>
                {!f.required ? (
                  <span className="bv-type-caption text-fg-muted">{t("form.optional")}</span>
                ) : null}
              </span>
            </label>
          );
        if (f.kind === "YES_NO" || f.kind === "SINGLE_CHOICE" || f.kind === "MULTI_CHOICE") {
          const options =
            f.kind === "YES_NO"
              ? [
                  { id: "yes", label: t("form.yes"), value: true },
                  { id: "no", label: t("form.no"), value: false },
                ]
              : (f.options ?? []).map((o) => ({
                  id: o.id,
                  label: localized(o.label, language),
                  value: o.id as unknown,
                }));
          const many = f.kind === "MULTI_CHOICE";
          const chosen = Array.isArray(value) ? (value as string[]) : [];
          return (
            <fieldset key={f.id} className="flex flex-col gap-2">
              <legend className="mb-2">{heading}</legend>
              <div
                className={f.kind === "YES_NO" ? "grid grid-cols-2 gap-2" : "flex flex-col gap-2"}
              >
                {options.map((o) => (
                  <label key={o.id} className={choice}>
                    <input
                      type={many ? "checkbox" : "radio"}
                      name={f.id}
                      checked={many ? chosen.includes(o.id) : value === o.value}
                      onChange={(e) =>
                        many
                          ? set(
                              f.id,
                              e.target.checked
                                ? [...chosen, o.id]
                                : chosen.filter((x) => x !== o.id),
                            )
                          : set(f.id, o.value)
                      }
                      className="size-5 shrink-0 accent-primary"
                    />
                    <span className="bv-type-body text-fg">{o.label}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          );
        }
        return (
          <label key={f.id} className="flex flex-col gap-2">
            {heading}
            {f.kind === "LONG_TEXT" ? (
              <textarea
                rows={4}
                maxLength={4000}
                value={typeof value === "string" ? value : ""}
                onChange={(e) => set(f.id, e.target.value)}
                className={input}
              />
            ) : (
              <input
                type={f.kind === "DATE" ? "date" : "text"}
                maxLength={500}
                value={typeof value === "string" ? value : ""}
                onChange={(e) => set(f.id, e.target.value || null)}
                className={input}
              />
            )}
          </label>
        );
      })}

      {form.signature ? (
        <label className="flex flex-col gap-2 border-t border-border pt-6">
          <span className="bv-type-label text-fg">{t("form.signature")}</span>
          <input
            value={signature}
            onChange={(e) => setSignature(e.target.value)}
            maxLength={120}
            autoComplete="name"
            className={input}
          />
          <span className="bv-type-caption text-fg-muted">{t("form.signatureHint")}</span>
        </label>
      ) : null}

      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
          {error}
        </p>
      ) : null}
      <button disabled={pending} className={`${primary} self-start`}>
        {t("form.send")}
      </button>
    </form>
  );
}
