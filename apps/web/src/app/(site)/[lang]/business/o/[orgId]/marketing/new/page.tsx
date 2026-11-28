import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { CampaignForm } from "../CampaignForm";

/** A new campaign: who gets it, where the link opens, and what it says. */
export default async function NewCampaignPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/marketing/new">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const places = unwrap(
    await (
      await portalApi(language)
    ).GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId } } }),
  ).items.map((l) => ({ id: l.id, name: l.name }));
  return (
    <>
      <h1 className="bv-type-title1">{t("portal.marketing.new")}</h1>
      <CampaignForm language={language} orgId={orgId} campaignId={null} places={places} />
    </>
  );
}
