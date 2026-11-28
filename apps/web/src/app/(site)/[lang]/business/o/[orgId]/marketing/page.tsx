import Link from "next/link";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { Section, buttonClass, listClass } from "@/components/business/ui";
import { DeleteLink, NewLink } from "./LinkForms";

/** Text message campaigns and counted links (plan 8.5). */
export default async function MarketingPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/marketing">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const [campaigns, links, locations] = await Promise.all([
    api.GET("/v1/orgs/{orgId}/marketing/campaigns", { params: { path: { orgId } } }).then(unwrap),
    api.GET("/v1/orgs/{orgId}/marketing/links", { params: { path: { orgId } } }).then(unwrap),
    api.GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId } } }).then(unwrap),
  ]);
  const places = locations.items.map((l) => ({ id: l.id, name: l.name }));
  const base = `/${language}/business/o/${orgId}/marketing`;
  return (
    <>
      <h1 className="bv-type-title1">{t("portal.marketing.title")}</h1>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("portal.marketing.intro")}</p>

      <Section title={t("portal.marketing.campaigns")}>
        <Link href={`${base}/new`} className={`self-start ${buttonClass}`}>
          {t("portal.marketing.new")}
        </Link>
        {campaigns.items.length === 0 ? (
          <p className="bv-type-body text-fg-muted">{t("portal.marketing.empty")}</p>
        ) : (
          <ul className={listClass}>
            {campaigns.items.map((c) => (
              <li key={c.id}>
                <Link
                  href={`${base}/${c.id}`}
                  className="flex min-h-14 flex-col gap-1 px-3 py-3 hover:bg-surface"
                >
                  <span className="flex flex-wrap items-baseline justify-between gap-2">
                    <span className="bv-type-body font-semibold">{c.name}</span>
                    <span className="bv-type-label text-fg-muted">
                      {t(`portal.marketing.status.${c.status}` as "portal.marketing.status.DRAFT")}
                    </span>
                  </span>
                  <span className="bv-type-caption text-fg-muted">
                    {t(`portal.marketing.audience.${c.audience}`)}
                    {c.stats.recipients > 0
                      ? ` · ${t("portal.marketing.stat.sent")} ${c.stats.sent}/${c.stats.recipients} · ${t("portal.marketing.stat.clicks")} ${c.stats.clicks}`
                      : ""}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section title={t("portal.marketing.links")}>
        <p className="bv-type-body max-w-2xl text-fg-muted">{t("portal.marketing.links.intro")}</p>
        <NewLink language={language} orgId={orgId} places={places} />
        {links.items.length === 0 ? (
          <p className="bv-type-body text-fg-muted">{t("portal.marketing.links.empty")}</p>
        ) : (
          <ul className={listClass}>
            {links.items.map((l) => (
              <li
                key={l.id}
                className="flex flex-wrap items-center justify-between gap-3 px-3 py-3"
              >
                <span className="flex min-w-0 flex-col gap-1">
                  <span className="bv-type-body font-semibold">{l.name}</span>
                  <span className="bv-type-label select-all break-all text-fg">{l.url}</span>
                  <span className="bv-type-caption text-fg-muted">
                    {t("portal.marketing.links.clicks", {
                      count: l.clicks,
                      week: l.clicksLast7Days,
                    })}
                  </span>
                </span>
                <DeleteLink language={language} orgId={orgId} linkId={l.id} />
              </li>
            ))}
          </ul>
        )}
      </Section>
    </>
  );
}
