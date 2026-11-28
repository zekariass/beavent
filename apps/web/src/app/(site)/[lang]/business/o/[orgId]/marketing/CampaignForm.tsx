"use client";

import { useActionState, useEffect, useState } from "react";
import type { Language } from "@beavent/i18n";
import { AUDIENCES, smsLength, smsSegments, type MarketingAudience } from "@beavent/venue";
import { buttonClass, Field, FormMessage, inputClass } from "@/components/business/ui";
import { translator } from "@/lib/i18n";
import { audienceCount, saveCampaign, type MarketingState } from "./actions";

export interface Place {
  id: string;
  name: string;
}

/**
 * A campaign draft: who gets it (with how many that is, as the choice is made), where the link opens, and the message
 * in each language with its length in text messages.
 */
export function CampaignForm({
  language,
  orgId,
  campaignId,
  places,
  initial,
}: {
  language: Language;
  orgId: string;
  campaignId: string | null;
  places: Place[];
  initial?: {
    name: string;
    audience: MarketingAudience;
    days?: number;
    locationId?: string;
    en: string;
    am: string;
  };
}) {
  const t = translator(language);
  const [state, action, pending] = useActionState(
    saveCampaign.bind(null, language, orgId, campaignId),
    {} as MarketingState,
  );
  const [audience, setAudience] = useState<MarketingAudience>(initial?.audience ?? "FOLLOWERS");
  const [days, setDays] = useState(initial?.days ? String(initial.days) : "");
  const [en, setEn] = useState(initial?.en ?? "");
  const [am, setAm] = useState(initial?.am ?? "");
  const [count, setCount] = useState<{ reachable: number; suppressed: number } | null>(null);
  const withDays = audience === "PAST_CLIENTS" || audience === "LAPSED_CLIENTS";

  useEffect(() => {
    let cancelled = false;
    const timer = setTimeout(() => {
      const n = Number(days);
      void audienceCount(
        language,
        orgId,
        audience,
        withDays && Number.isInteger(n) && n > 0 ? n : undefined,
      ).then((c) => !cancelled && setCount(c));
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [language, orgId, audience, days, withDays]);

  const counter = (text: string) =>
    text ? t("portal.marketing.chars", { count: smsLength(text), parts: smsSegments(text) }) : "";

  return (
    <form action={action} className="flex max-w-2xl flex-col gap-5">
      <Field label={t("portal.marketing.name")}>
        <input
          name="name"
          required
          maxLength={80}
          defaultValue={initial?.name}
          className={inputClass}
        />
      </Field>
      <Field label={t("portal.marketing.audience")} hint={t("portal.marketing.audience.hint")}>
        <select
          name="audience"
          value={audience}
          onChange={(e) => setAudience(e.target.value as MarketingAudience)}
          className={inputClass}
        >
          {AUDIENCES.map((a) => (
            <option key={a} value={a}>
              {t(`portal.marketing.audience.${a}`)}
            </option>
          ))}
        </select>
      </Field>
      {withDays ? (
        <Field
          label={t(`portal.marketing.days.${audience}` as "portal.marketing.days.PAST_CLIENTS")}
        >
          <input
            name="days"
            type="number"
            min={1}
            max={1095}
            value={days}
            placeholder={audience === "PAST_CLIENTS" ? "365" : "90"}
            onChange={(e) => setDays(e.target.value)}
            className={inputClass}
          />
        </Field>
      ) : null}
      {count ? (
        <p role="status" className="bv-type-label text-fg">
          {t("portal.marketing.reach", { count: count.reachable })}
          {count.suppressed > 0
            ? ` ${t("portal.marketing.reachStopped", { count: count.suppressed })}`
            : ""}
        </p>
      ) : null}
      <Field label={t("portal.marketing.place")}>
        <select name="locationId" defaultValue={initial?.locationId ?? ""} className={inputClass}>
          <option value="">{t("portal.marketing.placeFirst")}</option>
          {places.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label={t("portal.marketing.textEn")} hint={counter(en)}>
        <textarea
          name="textEn"
          rows={4}
          maxLength={480}
          value={en}
          onChange={(e) => setEn(e.target.value)}
          className={inputClass}
        />
      </Field>
      <Field label={t("portal.marketing.textAm")} hint={counter(am)}>
        <textarea
          name="textAm"
          rows={4}
          maxLength={480}
          lang="am"
          value={am}
          onChange={(e) => setAm(e.target.value)}
          className={inputClass}
        />
      </Field>
      <p className="bv-type-caption max-w-prose text-fg-muted">
        {t("portal.marketing.textHint", { token: "{name}" })}
      </p>
      <FormMessage error={state.error} />
      <button disabled={pending} className={`self-start ${buttonClass}`}>
        {t("portal.marketing.save")}
      </button>
    </form>
  );
}
