import { notFound } from "next/navigation";
import { formatDualDate, defaultDisplayPreferences, formatDualTime } from "@beavent/calendar";
import { calendarLanguage } from "@beavent/venue";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { Section } from "@/components/business/ui";
import { CampaignActions } from "../CampaignActions";
import { CampaignForm } from "../CampaignForm";

/** A campaign: the draft form while it is a draft; its message, state and results once it is on its way. */
export default async function CampaignPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/marketing/[campaignId]">) {
  const { lang, orgId, campaignId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const { data: campaign, response } = await api.GET(
    "/v1/orgs/{orgId}/marketing/campaigns/{campaignId}",
    { params: { path: { orgId, campaignId } } },
  );
  if (response.status === 404) notFound();
  if (!campaign) unwrap({ data: campaign, response });
  const c = campaign!;
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId } } }),
  );
  const places = locations.items.map((l) => ({ id: l.id, name: l.name }));
  const previews = unwrap(
    await api.GET("/v1/orgs/{orgId}/marketing/campaigns/{campaignId}/preview", {
      params: { path: { orgId, campaignId } },
    }),
  ).items;
  const prefs = defaultDisplayPreferences(calendarLanguage(language));
  const when = (instant?: string) =>
    instant
      ? `${formatDualDate(instant, prefs, { year: false }).primary}, ${formatDualTime(instant, prefs).primary}`
      : "";
  const stats = [
    ["recipients", c.stats.recipients],
    ["sent", c.stats.sent],
    ["failed", c.stats.failed],
    ["suppressed", c.stats.suppressed],
    ["clicks", c.stats.clicks],
  ] as const;
  return (
    <>
      <h1 className="bv-type-title1">{c.name}</h1>
      <p className="bv-type-label text-fg-muted">
        {t(`portal.marketing.status.${c.status}` as "portal.marketing.status.DRAFT")}
        {c.scheduledAt ? ` · ${when(c.scheduledAt)}` : ""}
      </p>
      <CampaignActions language={language} orgId={orgId} campaignId={c.id} status={c.status} />

      {c.status === "DRAFT" ? (
        <CampaignForm
          language={language}
          orgId={orgId}
          campaignId={c.id}
          places={places}
          initial={{
            name: c.name,
            audience: c.audience,
            days: c.days,
            locationId: c.locationId,
            en: c.texts.en ?? "",
            am: c.texts.am ?? "",
          }}
        />
      ) : (
        <p className="bv-type-body text-fg-muted">{t(`portal.marketing.audience.${c.audience}`)}</p>
      )}

      {c.stats.recipients > 0 ? (
        <Section title={t("portal.marketing.results")}>
          <dl className="grid max-w-xl grid-cols-2 gap-x-6 gap-y-2 sm:grid-cols-3">
            {stats.map(([key, value]) => (
              <div key={key} className="flex flex-col">
                <dt className="bv-type-caption text-fg-muted">
                  {t(`portal.marketing.stat.${key}`)}
                </dt>
                <dd className="bv-type-title2">{value}</dd>
              </div>
            ))}
          </dl>
        </Section>
      ) : null}

      <Section title={t("portal.marketing.preview")}>
        <ul className="flex max-w-xl flex-col gap-4">
          {previews.map((p) => (
            <li key={p.language} className="flex flex-col gap-1">
              <p className="bv-type-body rounded-lg bg-surface px-4 py-3 whitespace-pre-line break-words">
                {p.text}
              </p>
              <p className="bv-type-caption text-fg-muted">
                {t("portal.marketing.chars", { count: p.characters, parts: p.segments })}
              </p>
            </li>
          ))}
        </ul>
      </Section>
    </>
  );
}
