"use client";

import { useState, useTransition } from "react";
import type { Language } from "@beavent/i18n";
import {
  buttonClass,
  Field,
  FormMessage,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import { translator } from "@/lib/i18n";
import {
  cancelCampaign,
  deleteCampaign,
  scheduleCampaign,
  testCampaign,
  type MarketingState,
} from "./actions";

/** What can be done with a campaign now: test it, send it now or later, cancel what is scheduled, delete a draft. */
export function CampaignActions({
  language,
  orgId,
  campaignId,
  status,
}: {
  language: Language;
  orgId: string;
  campaignId: string;
  status: string;
}) {
  const t = translator(language);
  const [message, setMessage] = useState<MarketingState>({});
  const [later, setLater] = useState(false);
  const [at, setAt] = useState("");
  const [pending, start] = useTransition();
  const run = (job: () => Promise<MarketingState>) => start(async () => setMessage(await job()));

  if (status === "DRAFT")
    return (
      <div className="flex max-w-xl flex-col gap-4">
        <p className="bv-type-caption text-fg-muted">{t("portal.marketing.window")}</p>
        <FormMessage error={message.error} done={message.done} />
        <div className="flex flex-wrap gap-3">
          <button
            disabled={pending}
            className={buttonClass}
            onClick={() => run(() => scheduleCampaign(language, orgId, campaignId))}
          >
            {t("portal.marketing.sendNow")}
          </button>
          <button
            type="button"
            disabled={pending}
            className={secondaryButtonClass}
            onClick={() => setLater((v) => !v)}
          >
            {t("portal.marketing.sendLater")}
          </button>
          <button
            type="button"
            disabled={pending}
            className={secondaryButtonClass}
            onClick={() => run(() => testCampaign(language, orgId, campaignId))}
          >
            {t("portal.marketing.test")}
          </button>
          <button
            type="button"
            disabled={pending}
            className={secondaryButtonClass}
            onClick={() => run(() => deleteCampaign(language, orgId, campaignId))}
          >
            {t("portal.marketing.delete")}
          </button>
        </div>
        {later ? (
          <div className="flex flex-wrap items-end gap-3">
            <Field label={t("portal.marketing.sendAt")}>
              <input
                type="datetime-local"
                value={at}
                onChange={(e) => setAt(e.target.value)}
                className={inputClass}
              />
            </Field>
            <button
              disabled={pending || !at}
              className={buttonClass}
              onClick={() =>
                run(() => scheduleCampaign(language, orgId, campaignId, new Date(at).toISOString()))
              }
            >
              {t("portal.marketing.sendLater")}
            </button>
          </div>
        ) : null}
      </div>
    );

  if (status === "SCHEDULED" || status === "SENDING" || status === "SENT")
    return (
      <div className="flex flex-col gap-2">
        <FormMessage error={message.error} />
        <button
          type="button"
          disabled={pending}
          className={`self-start ${secondaryButtonClass}`}
          onClick={() => run(() => cancelCampaign(language, orgId, campaignId))}
        >
          {t("portal.marketing.cancel")}
        </button>
      </div>
    );
  return null;
}
