import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi } from "@/lib/session";
import { editorData } from "../editor";
import { ServiceForm } from "../ServiceForm";

export default async function NewServicePage({
  params,
}: PageProps<"/[lang]/o/[orgId]/services/new">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const data = await editorData(await portalApi(language), orgId, language, t);
  return (
    <>
      <h1 className="text-2xl font-semibold">{t("portal.service.newTitle")}</h1>
      <ServiceForm
        language={language}
        orgId={orgId}
        serviceId={null}
        version={null}
        {...data}
        initial={{
          nameAm: "",
          nameEn: "",
          categoryId: data.categories[0]?.id ?? "",
          groupId: "",
          locationIds: data.locations.map((l) => l.id),
          variants: [{ name: "", durationMinutes: 30, priceBirr: "" }],
        }}
      />
    </>
  );
}
