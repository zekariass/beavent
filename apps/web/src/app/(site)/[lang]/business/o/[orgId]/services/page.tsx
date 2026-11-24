import Link from "next/link";
import { formatDuration, localized } from "@beavent/i18n";
import { buttonClass, formatPrice } from "@/components/business/ui";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { SectionForm } from "./SectionForm";

export default async function ServicesPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/services">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const groups = unwrap(
    await api.GET("/v1/orgs/{orgId}/service-groups", { params: { path: { orgId } } }),
  );
  const services = unwrap(
    await api.GET("/v1/orgs/{orgId}/services", {
      params: { path: { orgId }, query: { limit: 100 } },
    }),
  );
  const sections = [
    ...groups.items.map((g) => ({
      id: g.id as string | undefined,
      name: localized(g.name, language),
    })),
    { id: undefined, name: t("portal.menu.ungrouped") },
  ];
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="bv-type-title1">{t("portal.menu.title")}</h1>
        <Link href={`/${language}/business/o/${orgId}/services/new`} className={buttonClass}>
          {t("portal.menu.addService")}
        </Link>
      </div>
      {services.items.length === 0 && (
        <p className="text-fg-muted">{t("portal.menu.noServices")}</p>
      )}
      {sections.map((section) => {
        const items = services.items.filter((s) => (s.groupId ?? undefined) === section.id);
        if (items.length === 0 && section.id === undefined) return null;
        return (
          <section key={section.id ?? "other"} className="flex flex-col gap-2">
            <h2 className="font-semibold">{section.name}</h2>
            <ul className="divide-y divide-border border-y border-border">
              {items.map((s) => (
                <li key={s.id}>
                  <Link
                    href={`/${language}/business/o/${orgId}/services/${s.id}`}
                    className="flex min-h-14 items-center justify-between gap-4 px-3 py-3 hover:bg-surface"
                  >
                    <span>{localized(s.name, language)}</span>
                    <span className="bv-type-label bv-numeric text-right text-fg-muted">
                      {s.variants
                        .map(
                          (v) =>
                            `${formatDuration(v.durationMinutes, t)} · ${formatPrice(v.priceMinor, s.currency, language)}`,
                        )
                        .join(" | ")}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
      <SectionForm
        language={language}
        orgId={orgId}
        labels={{ name: t("portal.menu.sectionName"), add: t("portal.menu.addSection") }}
      />
    </>
  );
}
