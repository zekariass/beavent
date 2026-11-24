import Link from "next/link";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi } from "@/lib/business/session";
import { attachableServices } from "../editor";
import { FormEditor } from "../FormEditor";

/** A new form, starting with the questions most consultation forms ask. */
export default async function NewFormPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/forms/new">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const services = await attachableServices(await portalApi(language), orgId, language);
  return (
    <>
      <div className="flex flex-col gap-1">
        <Link
          href={`/${language}/business/o/${orgId}/forms`}
          className="bv-type-label text-fg-muted hover:text-fg"
        >
          ‹ {t("portal.forms.title")}
        </Link>
        <h1 className="bv-type-title1">{t("portal.forms.newTitle")}</h1>
      </div>
      <FormEditor
        language={language}
        orgId={orgId}
        formId={null}
        version={null}
        services={services}
        initial={{
          nameAm: "",
          nameEn: "",
          purpose: "CONSULTATION",
          introAm: "",
          introEn: "",
          questions: [
            {
              id: "allergies",
              kind: "YES_NO",
              labelAm: "አለርጂ አለብዎት?",
              labelEn: "Do you have any allergies?",
              required: true,
              optionsAm: "",
              optionsEn: "",
              optionIds: [],
            },
            {
              id: "details",
              kind: "LONG_TEXT",
              labelAm: "ልናውቀው የሚገባ ሌላ ነገር",
              labelEn: "Anything else we should know",
              required: false,
              optionsAm: "",
              optionsEn: "",
              optionIds: [],
            },
          ],
          signature: true,
          serviceIds: [],
          frequency: "EVERY_TIME",
          frequencyDays: "",
        }}
      />
    </>
  );
}
