import { localized, type Translate } from "@beavent/i18n";
import type { ApiClient } from "@beavent/api-client";
import { publicApi, unwrap } from "@/lib/business/session";

/** Options and labels shared by the new-service and edit-service pages. */
export async function editorData(api: ApiClient, orgId: string, language: string, t: Translate) {
  const categories = unwrap(
    await publicApi(language).GET("/v1/ref/categories", { params: { query: { kind: "SERVICE" } } }),
  );
  const groups = unwrap(
    await api.GET("/v1/orgs/{orgId}/service-groups", { params: { path: { orgId } } }),
  );
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", {
      params: { path: { orgId }, query: { limit: 100 } },
    }),
  );
  return {
    categories: categories.items.map((c) => ({ id: c.id, label: localized(c.name, language) })),
    groups: groups.items.map((g) => ({ id: g.id, label: localized(g.name, language) })),
    locations: locations.items.map((l) => ({ id: l.id, label: l.name })),
    labels: {
      nameAm: t("portal.service.nameAm"),
      nameEn: t("portal.service.nameEn"),
      category: t("portal.service.category"),
      section: t("portal.service.section"),
      none: t("portal.service.none"),
      locations: t("portal.service.locations"),
      variants: t("portal.service.variants"),
      variantName: t("portal.service.variantName"),
      duration: t("portal.service.duration"),
      price: t("portal.service.price"),
      addVariant: t("portal.service.addVariant"),
      remove: t("portal.service.remove"),
      save: t("portal.service.save"),
      requirements: t("portal.service.requirements"),
      patchTest: t("portal.service.patchTest"),
      patchTestDays: t("portal.service.patchTestDays"),
      patchTestHint: t("portal.service.patchTestHint"),
    },
  };
}
