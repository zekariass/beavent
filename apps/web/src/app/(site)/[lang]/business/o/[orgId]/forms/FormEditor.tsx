"use client";

import type { Schemas } from "@beavent/api-client";
import { createTranslator, type Language } from "@beavent/i18n";
import { useActionState, useState } from "react";
import {
  Field,
  FormMessage,
  Section,
  buttonClass,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import { saveForm, type EditorValue, type FormState } from "./actions";

const KINDS: Schemas["FormFieldKind"][] = [
  "TEXT",
  "LONG_TEXT",
  "YES_NO",
  "SINGLE_CHOICE",
  "MULTI_CHOICE",
  "DATE",
  "CHECKBOX",
];
const PURPOSES: Schemas["FormPurpose"][] = ["CONSULTATION", "INTAKE", "CONSENT", "AFTERCARE"];
const FREQUENCIES: Schemas["FormFrequency"][] = ["EVERY_TIME", "ONCE", "EVERY_N_DAYS"];

type Question = EditorValue["questions"][number];

/** A short id for a new question, made when it is added (ids stay the same across versions). */
const newId = () => `q${Math.random().toString(36).slice(2, 8)}`;

/**
 * The form editor: name and purpose, the questions (answer type, the question in Amharic and English, options for
 * choices, required), a typed signature, the services it is attached to and how often it is asked.
 */
export function FormEditor({
  language,
  orgId,
  formId,
  initial,
  services,
  version,
}: {
  language: Language;
  orgId: string;
  formId: string | null;
  initial: EditorValue;
  services: { id: string; label: string }[];
  version: number | null;
}) {
  const t = createTranslator(language);
  const [value, setValue] = useState(initial);
  const [state, action, pending] = useActionState(
    saveForm.bind(null, language, orgId, formId),
    {} as FormState,
  );
  const setQuestion = (i: number, change: Partial<Question>) =>
    setValue((v) => ({
      ...v,
      questions: v.questions.map((q, j) => (j === i ? { ...q, ...change } : q)),
    }));
  const move = (i: number, by: number) =>
    setValue((v) => {
      const questions = [...v.questions];
      const [q] = questions.splice(i, 1);
      questions.splice(i + by, 0, q!);
      return { ...v, questions };
    });

  return (
    <form action={action} className="flex flex-col gap-8">
      <input type="hidden" name="payload" value={JSON.stringify(value)} />
      <div className="flex flex-col gap-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("portal.forms.nameAm")}>
            <input
              lang="am"
              value={value.nameAm}
              onChange={(e) => setValue({ ...value, nameAm: e.target.value })}
              maxLength={120}
              className={inputClass}
            />
          </Field>
          <Field label={t("portal.forms.nameEn")}>
            <input
              lang="en"
              value={value.nameEn}
              onChange={(e) => setValue({ ...value, nameEn: e.target.value })}
              maxLength={120}
              className={inputClass}
            />
          </Field>
        </div>
        <Field label={t("portal.forms.purpose")}>
          <select
            value={value.purpose}
            onChange={(e) =>
              setValue({ ...value, purpose: e.target.value as Schemas["FormPurpose"] })
            }
            className={`${inputClass} sm:max-w-xs`}
          >
            {PURPOSES.map((p) => (
              <option key={p} value={p}>
                {t(`portal.forms.purpose.${p}`)}
              </option>
            ))}
          </select>
        </Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("portal.forms.introAm")}>
            <textarea
              lang="am"
              rows={2}
              value={value.introAm}
              onChange={(e) => setValue({ ...value, introAm: e.target.value })}
              className={inputClass}
            />
          </Field>
          <Field label={t("portal.forms.introEn")}>
            <textarea
              lang="en"
              rows={2}
              value={value.introEn}
              onChange={(e) => setValue({ ...value, introEn: e.target.value })}
              className={inputClass}
            />
          </Field>
        </div>
      </div>

      <Section title={t("portal.forms.questions")}>
        <ol className="flex flex-col divide-y divide-border border-y border-border">
          {value.questions.map((q, i) => {
            const choice = q.kind === "SINGLE_CHOICE" || q.kind === "MULTI_CHOICE";
            return (
              <li key={q.id} className="flex flex-col gap-3 py-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="bv-type-label text-fg">
                    {t("portal.forms.question", { n: i + 1 })}
                  </span>
                  <span className="flex gap-2">
                    <button
                      type="button"
                      disabled={i === 0}
                      onClick={() => move(i, -1)}
                      className={secondaryButtonClass}
                    >
                      {t("portal.forms.moveUp")}
                    </button>
                    <button
                      type="button"
                      disabled={i === value.questions.length - 1}
                      onClick={() => move(i, 1)}
                      className={secondaryButtonClass}
                    >
                      {t("portal.forms.moveDown")}
                    </button>
                    <button
                      type="button"
                      disabled={value.questions.length === 1}
                      onClick={() =>
                        setValue({ ...value, questions: value.questions.filter((_, j) => j !== i) })
                      }
                      className={secondaryButtonClass}
                    >
                      {t("portal.calendar.remove")}
                    </button>
                  </span>
                </div>
                <Field label={t("portal.forms.kind")}>
                  <select
                    value={q.kind}
                    onChange={(e) =>
                      setQuestion(i, { kind: e.target.value as Schemas["FormFieldKind"] })
                    }
                    className={`${inputClass} sm:max-w-xs`}
                  >
                    {KINDS.map((k) => (
                      <option key={k} value={k}>
                        {t(`portal.forms.kind.${k}`)}
                      </option>
                    ))}
                  </select>
                </Field>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label={t("portal.forms.labelAm")}>
                    <input
                      lang="am"
                      value={q.labelAm}
                      onChange={(e) => setQuestion(i, { labelAm: e.target.value })}
                      maxLength={500}
                      className={inputClass}
                    />
                  </Field>
                  <Field label={t("portal.forms.labelEn")}>
                    <input
                      lang="en"
                      value={q.labelEn}
                      onChange={(e) => setQuestion(i, { labelEn: e.target.value })}
                      maxLength={500}
                      className={inputClass}
                    />
                  </Field>
                </div>
                {choice ? (
                  <div className="grid gap-3 sm:grid-cols-2">
                    <Field label={t("portal.forms.optionsAm")}>
                      <textarea
                        lang="am"
                        rows={3}
                        value={q.optionsAm}
                        onChange={(e) => setQuestion(i, { optionsAm: e.target.value })}
                        className={inputClass}
                      />
                    </Field>
                    <Field label={t("portal.forms.optionsEn")}>
                      <textarea
                        lang="en"
                        rows={3}
                        value={q.optionsEn}
                        onChange={(e) => setQuestion(i, { optionsEn: e.target.value })}
                        className={inputClass}
                      />
                    </Field>
                  </div>
                ) : null}
                <label className="bv-type-body flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={q.required}
                    onChange={(e) => setQuestion(i, { required: e.target.checked })}
                    className="size-5 accent-primary"
                  />
                  {t("portal.forms.required")}
                </label>
              </li>
            );
          })}
        </ol>
        <button
          type="button"
          onClick={() =>
            setValue({
              ...value,
              questions: [
                ...value.questions,
                {
                  id: newId(),
                  kind: "TEXT",
                  labelAm: "",
                  labelEn: "",
                  required: false,
                  optionsAm: "",
                  optionsEn: "",
                  optionIds: [],
                },
              ],
            })
          }
          className={`${secondaryButtonClass} self-start`}
        >
          {t("portal.forms.addQuestion")}
        </button>
        <label className="bv-type-body flex items-center gap-2">
          <input
            type="checkbox"
            checked={value.signature}
            onChange={(e) => setValue({ ...value, signature: e.target.checked })}
            className="size-5 accent-primary"
          />
          {t("portal.forms.signature")}
        </label>
      </Section>

      <Section title={t("portal.forms.attach")}>
        <fieldset className="grid gap-2 sm:grid-cols-2">
          <legend className="sr-only">{t("portal.forms.attach")}</legend>
          {services.map((s) => (
            <label key={s.id} className="bv-type-body flex items-center gap-2">
              <input
                type="checkbox"
                checked={value.serviceIds.includes(s.id)}
                onChange={(e) =>
                  setValue({
                    ...value,
                    serviceIds: e.target.checked
                      ? [...value.serviceIds, s.id]
                      : value.serviceIds.filter((id) => id !== s.id),
                  })
                }
                className="size-5 accent-primary"
              />
              {s.label}
            </label>
          ))}
        </fieldset>
        <div className="flex flex-wrap items-end gap-3">
          <Field label={t("portal.forms.frequency")}>
            <select
              value={value.frequency}
              onChange={(e) =>
                setValue({ ...value, frequency: e.target.value as Schemas["FormFrequency"] })
              }
              className={inputClass}
            >
              {FREQUENCIES.map((f) => (
                <option key={f} value={f}>
                  {t(`portal.forms.frequency.${f}`)}
                </option>
              ))}
            </select>
          </Field>
          {value.frequency === "EVERY_N_DAYS" ? (
            <div className="w-32">
              <Field label={t("portal.forms.days")}>
                <input
                  type="number"
                  min={1}
                  max={3650}
                  value={value.frequencyDays}
                  onChange={(e) => setValue({ ...value, frequencyDays: e.target.value })}
                  className={inputClass}
                />
              </Field>
            </div>
          ) : null}
        </div>
      </Section>

      {version ? (
        <p className="bv-type-caption text-fg-muted">
          {t("portal.forms.versionNote", { n: version })}
        </p>
      ) : null}
      <div className="flex items-center gap-4">
        <button disabled={pending} className={buttonClass}>
          {t("portal.calendar.save")}
        </button>
        <FormMessage
          error={state.error}
          done={state.done ? t("portal.clients.saved") : undefined}
        />
      </div>
    </form>
  );
}
