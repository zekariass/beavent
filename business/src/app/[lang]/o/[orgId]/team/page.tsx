import Link from "next/link";
import { localized } from "@beavent/i18n";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/session";
import { AddStaffForm } from "./AddStaffForm";

export default async function TeamPage({ params }: PageProps<"/[lang]/o/[orgId]/team">) {
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
      <h1 className="text-2xl font-semibold">{t("portal.team.title")}</h1>
      {staff.items.length === 0 ? (
        <p className="opacity-80">{t("portal.team.empty")}</p>
      ) : (
        <ul className="divide-y divide-[var(--bv-color-border)] rounded-lg border border-[var(--bv-color-border)]">
          {staff.items.map((s) => (
            <li key={s.id}>
              <Link
                href={`/${language}/o/${orgId}/team/${s.id}`}
                className="flex justify-between gap-4 p-3 hover:bg-black/5"
              >
                <span>{s.displayName}</span>
                <span className="text-sm opacity-70">{s.jobTitle ?? ""}</span>
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
