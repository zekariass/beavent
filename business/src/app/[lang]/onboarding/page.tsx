import { localized } from "@beavent/i18n";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, publicApi, unwrap } from "@/lib/session";
import { OnboardingForm, type Option } from "./OnboardingForm";

export default async function OnboardingPage({ params }: PageProps<"/[lang]/onboarding">) {
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  await portalApi(language); // signed in only
  const ref = publicApi(language);
  const categories = unwrap(
    await ref.GET("/v1/ref/categories", { params: { query: { kind: "BUSINESS" } } }),
  );
  const regions = unwrap(
    await ref.GET("/v1/ref/admin-areas", { params: { query: { countryCode: "ET" } } }),
  );
  // Addis Ababa's sub-cities first (the pilot city), then the other regions.
  const addis = regions.items.find((r) => r.path === "ET.AA");
  const subCities = addis
    ? unwrap(
        await ref.GET("/v1/ref/admin-areas", {
          params: { query: { countryCode: "ET", parentId: addis.id } },
        }),
      ).items
    : [];
  const name = (a: { name: string; nameI18n?: Record<string, string> }) =>
    localized(a.nameI18n, language) || a.name;
  const areas: Option[] = [
    ...subCities.map((a) => ({ id: a.id, label: `${name(a)} — ${addis ? name(addis) : ""}` })),
    ...regions.items.filter((r) => r.path !== "ET.AA").map((r) => ({ id: r.id, label: name(r) })),
  ];
  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-6 py-12">
      <h1 className="text-2xl font-semibold">{t("portal.onboarding.title")}</h1>
      <OnboardingForm
        language={language}
        categories={categories.items.map((c) => ({ id: c.id, label: localized(c.name, language) }))}
        areas={areas}
        labels={{
          business: t("portal.onboarding.business"),
          name: t("portal.onboarding.name"),
          category: t("portal.onboarding.category"),
          legal: t("portal.onboarding.legal"),
          registeredName: t("portal.onboarding.registeredName"),
          taxRegime: t("portal.onboarding.taxRegime"),
          tin: t("portal.onboarding.tin"),
          vatNumber: t("portal.onboarding.vatNumber"),
          location: t("portal.onboarding.location"),
          locationName: t("portal.onboarding.locationName"),
          subCity: t("portal.onboarding.subCity"),
          landmark: t("portal.onboarding.landmark"),
          submit: t("portal.onboarding.submit"),
        }}
      />
    </main>
  );
}
