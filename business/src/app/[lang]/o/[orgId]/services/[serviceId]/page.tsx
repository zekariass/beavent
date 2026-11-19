import { localized } from "@beavent/i18n";
import { secondaryButtonClass, toBirr } from "@/components/ui";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/session";
import { archiveService } from "../actions";
import { editorData } from "../editor";
import { ServiceForm } from "../ServiceForm";
import { StaffPrices } from "./StaffPrices";

export default async function EditServicePage({
  params,
}: PageProps<"/[lang]/o/[orgId]/services/[serviceId]">) {
  const { lang, orgId, serviceId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const service = unwrap(
    await api.GET("/v1/orgs/{orgId}/services/{serviceId}", {
      params: { path: { orgId, serviceId } },
    }),
  );
  const data = await editorData(api, orgId, language, t);
  const team = unwrap(await api.GET("/v1/orgs/{orgId}/staff", { params: { path: { orgId } } }));
  const prices = unwrap(
    await api.GET("/v1/orgs/{orgId}/services/{serviceId}/prices", {
      params: { path: { orgId, serviceId } },
    }),
  );
  const current: Record<string, string> = {};
  for (const p of prices.items) {
    if (p.staffId && !p.locationId) current[`${p.staffId}:${p.variantId}`] = toBirr(p.priceMinor);
  }
  const performers = team.items.filter((s) => service.staffIds.includes(s.id));
  return (
    <>
      <h1 className="text-2xl font-semibold">{localized(service.name, language)}</h1>
      <ServiceForm
        language={language}
        orgId={orgId}
        serviceId={serviceId}
        version={service.version}
        {...data}
        initial={{
          nameAm: service.name.am ?? "",
          nameEn: service.name.en ?? "",
          categoryId: service.categoryId,
          groupId: service.groupId ?? "",
          locationIds: service.locationIds,
          variants: service.variants.map((v) => ({
            id: v.id,
            name: localized(v.name, language),
            durationMinutes: v.durationMinutes,
            priceBirr: toBirr(v.priceMinor),
          })),
        }}
      />
      <StaffPrices
        language={language}
        orgId={orgId}
        serviceId={serviceId}
        variants={service.variants.map((v, i) => ({
          id: v.id,
          label: localized(v.name, language) || `${i + 1}`,
          defaultBirr: toBirr(v.priceMinor),
        }))}
        staff={(performers.length > 0 ? performers : team.items).map((s) => ({
          id: s.id,
          name: s.displayName,
        }))}
        current={current}
        labels={{
          title: t("portal.prices.title"),
          hint: t("portal.prices.hint"),
          save: t("portal.prices.save"),
        }}
      />
      <form action={archiveService.bind(null, language, orgId, serviceId)}>
        <button className={secondaryButtonClass}>{t("portal.service.archive")}</button>
      </form>
    </>
  );
}
