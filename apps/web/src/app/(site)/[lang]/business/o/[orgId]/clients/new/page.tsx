import Link from "next/link";
import { routeLanguage, translator } from "@/lib/i18n";
import { NewClientForm } from "./NewClientForm";

/** Add a client by hand (P-13): name, and optionally phone, email, language and a first note. */
export default async function NewClientPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/clients/new">) {
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
        <h1 className="bv-type-title1">{t("portal.clients.newTitle")}</h1>
      </div>
      <NewClientForm
        language={language}
        orgId={orgId}
        labels={{
          name: t("portal.clients.name"),
          phone: t("portal.calendar.customerPhone"),
          email: t("portal.clients.emailOptional"),
          language: t("portal.clients.language"),
          languageUnset: t("portal.clients.languageUnset"),
          note: t("portal.clients.noteOptional"),
          add: t("portal.clients.add"),
        }}
      />
    </>
  );
}
