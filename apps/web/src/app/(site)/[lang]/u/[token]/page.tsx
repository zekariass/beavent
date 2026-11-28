import type { Metadata } from "next";
import { connection } from "next/server";
import { SiteHeader } from "@/components/SiteHeader";
import { StopForm } from "@/components/customer/StopForm";
import { publicApi } from "@/lib/customer/session";
import { routeLanguage, translator } from "@/lib/i18n";

export const metadata: Metadata = {
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

/**
 * Stop messages from a business (the link at the end of every campaign message). Opening the page changes nothing,
 * so a link preview cannot stop anyone by accident; the button does.
 */
export default async function StopPage({ params }: PageProps<"/[lang]/u/[token]">) {
  await connection();
  const { lang, token } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const { data } = await publicApi(language)
    .GET("/v1/public/marketing/stop/{token}", { params: { path: { token } }, cache: "no-store" })
    .catch(() => ({ data: undefined }));
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/u/${token}`} />
      <main className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-4 px-4 pb-12 sm:px-6">
        {data ? (
          <>
            <h1 className="bv-type-title1 text-fg">
              {t("stop.title", { business: data.business })}
            </h1>
            <p className="bv-type-body max-w-prose text-fg-muted">
              {t("stop.body", { business: data.business })}
            </p>
            <StopForm language={language} token={token} business={data.business} />
          </>
        ) : (
          <p className="bv-type-body text-fg">{t("stop.invalid")}</p>
        )}
      </main>
    </div>
  );
}
