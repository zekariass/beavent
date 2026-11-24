"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import { useActionState, useState, useTransition } from "react";
import {
  Field,
  FormMessage,
  buttonClass,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import { recordPatchTest, removePatchTest, type FormState } from "../actions";

/** Records a patch test done today for one of the services that need one. */
export function PatchTestForm({
  language,
  orgId,
  customerId,
  services,
}: {
  language: Language;
  orgId: string;
  customerId: string;
  services: { id: string; label: string }[];
}) {
  const t = createTranslator(language);
  const [saved, setSaved] = useState(0);
  const [state, action, pending] = useActionState(async (prev: FormState, form: FormData) => {
    const result = await recordPatchTest(language, orgId, customerId, prev, form);
    if (result.done) setSaved((n) => n + 1);
    return result;
  }, {} as FormState);
  return (
    <form key={saved} action={action} className="flex flex-col gap-3">
      <span className="bv-type-label text-fg">{t("portal.clients.recordPatchTest")}</span>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t("portal.calendar.service")}>
          <select name="serviceId" className={inputClass}>
            {services.map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label={t("portal.clients.patch.result")}>
          <select name="result" defaultValue="PASS" className={inputClass}>
            {(["PASS", "FAIL", "PENDING"] as const).map((r) => (
              <option key={r} value={r}>
                {t(`portal.clients.patch.${r}`)}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <Field label={t("portal.clients.noteOptional")}>
        <input name="notes" maxLength={1000} className={inputClass} />
      </Field>
      <div className="flex items-center gap-4">
        <button disabled={pending} className={buttonClass}>
          {t("portal.clients.recordPatchTest")}
        </button>
        <FormMessage error={state.error} />
      </div>
    </form>
  );
}

export function RemovePatchTest({
  language,
  orgId,
  customerId,
  testId,
}: {
  language: Language;
  orgId: string;
  customerId: string;
  testId: string;
}) {
  const t = createTranslator(language);
  const [pending, start] = useTransition();
  return (
    <button
      type="button"
      disabled={pending}
      onClick={() => start(() => removePatchTest(language, orgId, customerId, testId))}
      className={secondaryButtonClass}
    >
      {t("portal.calendar.remove")}
    </button>
  );
}
