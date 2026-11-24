import Link from "next/link";
import { localized } from "@beavent/i18n";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { AddStaffForm } from "./AddStaffForm";

export default async function TeamPage({ params }: PageProps<"/[lang]/business/o/[orgId]/team">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const staff = unwrap(await api.GET("/v1/orgs/{orgId}/staff", { params: { path: { orgId } } }));
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", {
      params: { path: { orgId }, query: { limit: 100 } },
    }),
  );
  const services = unwrap(
    await api.GET("/v1/orgs/{orgId}/services", {
      params: { path: { orgId }, query: { limit: 100 } },
    }),
  );
  return (
    <>
      <h1 className="bv-type-title1">{t("portal.team.title")}</h1>
      {staff.items.length === 0 ? (
        <p className="text-fg-muted">{t("portal.team.empty")}</p>
      ) : (
        <ul className="divide-y divide-border border-y border-border">
          {staff.items.map((s) => (
            <li key={s.id}>
              <Link
                href={`/${language}/business/o/${orgId}/team/${s.id}`}
                className="flex min-h-14 items-center justify-between gap-4 px-3 py-3 hover:bg-surface"
              >
                <span>{s.displayName}</span>
                <span className="text-sm text-fg-muted">{s.jobTitle ?? ""}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <AddStaffForm
        language={language}
        orgId={orgId}
        locations={locations.items.map((l) => ({ id: l.id, label: l.name }))}
        services={services.items.map((s) => ({ id: s.id, label: localized(s.name, language) }))}
        labels={{
          add: t("portal.team.add"),
          name: t("portal.team.name"),
          jobTitle: t("portal.team.jobTitle"),
          locations: t("portal.service.locations"),
          services: t("portal.team.services"),
        }}
      />
    </>
  );
}
