import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { sellables } from "@/lib/business/products";
import { CheckoutForm } from "./CheckoutForm";

/** Checking a visit out at the counter (plan 4.1): the bill, a tip for the team, the cash, the receipt. */
export default async function CheckoutPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/checkout/[appointmentId]">) {
  const { lang, orgId, appointmentId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const bill = unwrap(
    await api.GET("/v1/orgs/{orgId}/appointments/{appointmentId}/checkout", {
      params: { path: { orgId, appointmentId } },
    }),
  );
  // Goods for sale at the visit's location; staff who may not list products just get no section.
  const { data: catalogue } = await api.GET("/v1/orgs/{orgId}/products", {
    params: { path: { orgId }, query: { locationId: bill.locationId } },
  });
  const products = sellables(catalogue?.items ?? [], language).filter(
    (i) => i.onHand === null || i.onHand > 0,
  );
  return (
    <>
      <h1 className="bv-type-title1">{t("counter.checkout.title")}</h1>
      <CheckoutForm language={language} orgId={orgId} bill={bill} products={products} />
    </>
  );
}
