import { portalApi, unwrap } from "@/lib/business/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { SaleForm } from "./SaleForm";

/** Taking the payment for a walk-in sale. */
export default async function SalePage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/sell/[orderId]">) {
  const { lang, orgId, orderId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const sale = unwrap(
    await (
      await portalApi(language)
    ).GET("/v1/orgs/{orgId}/sales/{orderId}", { params: { path: { orgId, orderId } } }),
  );
  return (
    <>
      <h1 className="bv-type-title1">{t("sell.title")}</h1>
      <SaleForm language={language} orgId={orgId} sale={sale} />
    </>
  );
}
