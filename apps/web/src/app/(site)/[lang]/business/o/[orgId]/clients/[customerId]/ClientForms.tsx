"use client";

import type { Schemas } from "@beavent/api-client";
import { LANGUAGE_NAMES, createTranslator, type Language } from "@beavent/i18n";
import { useActionState, useState, useTransition } from "react";
import {
  Field,
  FormMessage,
  buttonClass,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import { addNote, removeNote, saveDetails, saveTags, type FormState } from "../actions";

const KINDS = ["GENERAL", "ALLERGY", "ALERT", "MEDICAL", "PREFERENCE"] as const;

/** Adds a note; the form clears after it is saved (a new key remounts it). */
export function NoteForm({
  language,
  orgId,
  customerId,
}: {
  language: Language;
  orgId: string;
  customerId: string;
}) {
  const t = createTranslator(language);
  const [saved, setSaved] = useState(0);
  const [state, action, pending] = useActionState(async (prev: FormState, form: FormData) => {
    const result = await addNote(language, orgId, customerId, prev, form);
    if (result.done) setSaved((n) => n + 1);
    return result;
  }, {} as FormState);
  return (
    <form key={saved} action={action} className="flex flex-col gap-3">
      <Field label={t("portal.clients.noteText")}>
        <textarea name="body" required rows={2} maxLength={2000} className={inputClass} />
      </Field>
      <div className="flex flex-wrap items-end gap-4">
        <Field label={t("portal.clients.noteKind")}>
          <select name="kind" defaultValue="GENERAL" className={inputClass}>
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {t(`portal.clients.note.${k}`)}
              </option>
            ))}
          </select>
        </Field>
        <label className="bv-type-body flex min-h-11 items-center gap-2">
          <input type="checkbox" name="pinned" className="size-5 accent-primary" />
          {t("portal.clients.pin")}
        </label>
        <button disabled={pending} className={buttonClass}>
          {t("portal.clients.addNote")}
        </button>
      </div>
      <FormMessage error={state.error} />
    </form>
  );
}

export function RemoveNote({
  language,
  orgId,
  customerId,
  noteId,
}: {
  language: Language;
  orgId: string;
  customerId: string;
  noteId: string;
}) {
  const t = createTranslator(language);
  const [pending, start] = useTransition();
  return (
    <button
      type="button"
      disabled={pending}
      onClick={() => start(() => removeNote(language, orgId, customerId, noteId))}
      className={secondaryButtonClass}
    >
      {t("portal.calendar.remove")}
    </button>
  );
}

export function TagsForm({
  language,
  orgId,
  customerId,
  tags,
}: {
  language: Language;
  orgId: string;
  customerId: string;
  tags: string;
}) {
  const t = createTranslator(language);
  const [state, action, pending] = useActionState(
    saveTags.bind(null, language, orgId, customerId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex flex-col gap-3">
      <div className="flex flex-col gap-2">
        <input
          name="tags"
          defaultValue={tags}
          maxLength={900}
          aria-label={t("portal.clients.tags")}
          aria-describedby="tags-hint"
          className={inputClass}
        />
        <span id="tags-hint" className="bv-type-caption text-fg-muted">
          {t("portal.clients.tagsHint")}
        </span>
      </div>
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

export function DetailsForm({
  language,
  orgId,
  client,
}: {
  language: Language;
  orgId: string;
  client: Schemas["Client"];
}) {
  const t = createTranslator(language);
  const [blocked, setBlocked] = useState(client.blocked);
  const [state, action, pending] = useActionState(
    saveDetails.bind(null, language, orgId, client.id, client.you.contactDetails),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-xl flex-col gap-4">
      <Field label={t("portal.clients.name")}>
        <input
          name="name"
          required
          maxLength={120}
          defaultValue={client.name}
          className={inputClass}
        />
      </Field>
      {client.you.contactDetails ? (
        <>
          <Field label={t("portal.clients.phone")}>
            <input
              name="phone"
              type="tel"
              inputMode="tel"
              defaultValue={client.phone ?? ""}
              className={inputClass}
            />
          </Field>
          <Field label={t("portal.clients.email")}>
            <input
              name="email"
              type="email"
              defaultValue={client.email ?? ""}
              className={inputClass}
            />
          </Field>
        </>
      ) : null}
      <Field label={t("portal.clients.language")}>
        <select
          name="preferredLanguage"
          defaultValue={client.preferredLanguage ?? ""}
          className={inputClass}
        >
          <option value="">{t("portal.clients.languageUnset")}</option>
          <option value="am">{LANGUAGE_NAMES.am}</option>
          <option value="en">{LANGUAGE_NAMES.en}</option>
        </select>
      </Field>
      <label className="flex items-start gap-3">
        <input
          type="checkbox"
          name="blocked"
          checked={blocked}
          onChange={(e) => setBlocked(e.target.checked)}
          className="mt-1 size-5 accent-primary"
        />
        <span className="flex flex-col">
          <span className="bv-type-body text-fg">{t("portal.clients.blocked")}</span>
          <span className="bv-type-caption text-fg-muted">{t("portal.clients.blockedHint")}</span>
        </span>
      </label>
      {blocked ? (
        <Field label={t("portal.clients.blockReason")}>
          <input
            name="blockReason"
            maxLength={500}
            defaultValue={client.blockReason ?? ""}
            className={inputClass}
          />
        </Field>
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
