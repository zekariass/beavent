import { PrintButton } from "@/components/receipt/PrintButton";
import { ReceiptSheet } from "@/components/receipt/ReceiptSheet";
import { portalApi, unwrap } from "@/lib/business/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { SendReceipt } from "./SendReceipt";

/** A sale's receipt for the front desk: to print, and to send to the customer (plan 4.3). */
export default async function ReceiptPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/receipts/[orderId]">) {
  const { lang, orgId, orderId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const receipt = unwrap(
    await (
      await portalApi(language)
    ).GET("/v1/orgs/{orgId}/orders/{orderId}/receipt", { params: { path: { orgId, orderId } } }),
  );
  return (
    <>
      <div className="print:hidden">
        <h1 className="bv-type-title1">{t("receipt.title")}</h1>
      </div>
      <ReceiptSheet receipt={receipt} language={language} />
      <div className="mx-auto flex w-full max-w-sm flex-col gap-3 print:hidden">
        <PrintButton label={t("receipt.print")} />
        <SendReceipt language={language} orgId={orgId} orderId={orderId} />
      </div>
    </>
  );
}
