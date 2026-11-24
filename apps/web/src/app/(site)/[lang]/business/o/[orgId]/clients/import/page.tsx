import Link from "next/link";
import { routeLanguage, translator } from "@/lib/i18n";
import { ImportForm } from "./ImportForm";

/** Import clients (P-13): choose a CSV file, see what it holds (new, already here, problems), then add them. */
export default async function ImportPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/clients/import">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  return (
    <>
      <div className="flex flex-col gap-1">
        <Link
          href={`/${language}/business/o/${orgId}/clients`}
          className="bv-type-label text-fg-muted hover:text-fg"
        >
          ‹ {t("portal.clients.title")}
        </Link>
        <h1 className="bv-type-title1">{t("portal.clients.importTitle")}</h1>
      </div>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("portal.clients.importIntro")}</p>
      <ImportForm language={language} orgId={orgId} />
    </>
  );
}
