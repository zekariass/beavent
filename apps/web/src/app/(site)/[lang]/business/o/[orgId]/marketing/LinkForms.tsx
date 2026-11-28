"use client";

import { useActionState, useTransition } from "react";
import type { Language } from "@beavent/i18n";
import {
  buttonClass,
  Field,
  FormMessage,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import { translator } from "@/lib/i18n";
import type { Place } from "./CampaignForm";
import { createLink, deleteLink, type MarketingState } from "./actions";

/** Make a counted link to one of the business's places. */
export function NewLink({
  language,
  orgId,
  places,
}: {
  language: Language;
  orgId: string;
  places: Place[];
}) {
  const t = translator(language);
  const [state, action, pending] = useActionState(
    createLink.bind(null, language, orgId),
    {} as MarketingState,
  );
  return (
    <form action={action} className="flex max-w-xl flex-wrap items-end gap-3">
      <div className="min-w-48 flex-1">
        <Field label={t("portal.marketing.links.name")}>
          <input name="name" required maxLength={80} className={inputClass} />
        </Field>
      </div>
      <Field label={t("portal.marketing.place")}>
        <select name="locationId" className={inputClass} defaultValue="">
          <option value="">{t("portal.marketing.placeFirst")}</option>
          {places.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </Field>
      <button disabled={pending} className={buttonClass}>
        {t("portal.marketing.links.create")}
      </button>
      <div className="basis-full">
        <FormMessage error={state.error} />
      </div>
    </form>
  );
}

export function DeleteLink({
  language,
  orgId,
  linkId,
}: {
  language: Language;
  orgId: string;
  linkId: string;
}) {
  const t = translator(language);
  const [pending, start] = useTransition();
  return (
    <button
      type="button"
      disabled={pending}
      className={secondaryButtonClass}
      onClick={() => start(async () => void (await deleteLink(language, orgId, linkId)))}
    >
      {t("portal.marketing.links.delete")}
    </button>
  );
}
