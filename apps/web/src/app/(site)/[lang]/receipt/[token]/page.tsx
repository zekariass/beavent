import type { Metadata } from "next";
import { connection } from "next/server";
import { PrintButton } from "@/components/receipt/PrintButton";
import { ReceiptSheet } from "@/components/receipt/ReceiptSheet";
import { SiteHeader } from "@/components/SiteHeader";
import { publicApi } from "@/lib/customer/session";
import { routeLanguage, translator } from "@/lib/i18n";

export const metadata: Metadata = {
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

/** A receipt opened from the link the business sent (plan 4.3): no sign-in, the link is the credential. */
export default async function ReceiptPage({ params }: PageProps<"/[lang]/receipt/[token]">) {
  await connection();
  const { lang, token } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const { data: receipt } = await publicApi(language).GET("/v1/public/receipts/{token}", {
    params: { path: { token } },
  });
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <div className="print:hidden">
        <SiteHeader language={language} path={`/receipt/${token}`} />
      </div>
      <main className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-6 px-4 py-8 sm:px-6">
        {receipt ? (
          <>
            <ReceiptSheet receipt={receipt} language={language} />
            <div className="flex justify-center print:hidden">
              <PrintButton label={t("receipt.print")} />
            </div>
          </>
        ) : (
          <p role="status" className="bv-type-body">
            {t("receipt.notValid")}
          </p>
        )}
      </main>
    </div>
  );
}
