import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { Section, listClass } from "@/components/business/ui";
import { MarketplaceButton } from "./MarketplaceButton";

/** Being found in search: where the business stands, what it still needs, and the one thing it can do next. */
export default async function MarketplacePage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/marketplace">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const status = unwrap(
    await (
      await portalApi(language)
    ).GET("/v1/orgs/{orgId}/marketplace", { params: { path: { orgId } } }),
  );
  const missing = status.requirements.filter((r) => !r.met);
  const button = (requested: boolean, label: string, primary?: boolean) => (
    <MarketplaceButton
      language={language}
      orgId={orgId}
      requested={requested}
      label={label}
      primary={primary}
    />
  );
  return (
    <>
      <h1 className="bv-type-title1">{t("portal.marketplace.title")}</h1>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("portal.marketplace.intro")}</p>

      <Section title={t(`portal.marketplace.status.${status.status}`)}>
        {status.note ? (
          <p className="bv-type-body max-w-2xl">
            {t("portal.marketplace.note", { note: status.note })}
          </p>
        ) : null}
        {status.status === "PENDING_REVIEW" ? (
          <>
            <p className="bv-type-body text-fg-muted">{t("portal.marketplace.pendingHint")}</p>
            {button(false, t("portal.marketplace.withdraw"))}
          </>
        ) : status.status === "LISTED" ? (
          button(false, t("portal.marketplace.unlist"))
        ) : status.status === "DELISTED" ? (
          <p className="bv-type-body text-fg-muted">{t("portal.marketplace.delistedHint")}</p>
        ) : (
          <>
            {missing.length > 0 ? (
              <>
                <p className="bv-type-label text-fg-muted">{t("portal.marketplace.needs")}</p>
                <ul className={listClass}>
                  {status.requirements.map((r) => (
                    <li key={r.code} className="flex items-center justify-between gap-4 py-3">
                      <span className="bv-type-body">{t(`portal.marketplace.req.${r.code}`)}</span>
                      <span className={`bv-type-label ${r.met ? "text-success" : "text-fg-muted"}`}>
                        {r.met ? t("portal.marketplace.met") : t("portal.marketplace.missing")}
                      </span>
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
            {status.canRequest ? button(true, t("portal.marketplace.ask"), true) : null}
          </>
        )}
      </Section>
    </>
  );
}
