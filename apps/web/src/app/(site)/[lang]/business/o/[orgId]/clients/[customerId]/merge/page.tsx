import type { Schemas } from "@beavent/api-client";
import type { Language } from "@beavent/i18n";
import Link from "next/link";
import { redirect } from "next/navigation";
import { inputBaseClass, listClass, secondaryButtonLargeClass } from "@/components/business/ui";
import { dayOf } from "@/components/business/when";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { MergeForm } from "./MergeForm";

/**
 * Merging a duplicate (P-13): find the other record (people with a similar name are suggested), compare the two side
 * by side, read that it cannot be undone, merge. Either record can be the one that stays.
 */
export default async function MergePage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/clients/[customerId]/merge">) {
  const { lang, orgId, customerId } = await params;
  const query = (await searchParams) as { q?: string; with?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const base = `/${language}/business/o/${orgId}/clients`;
  const client = unwrap(
    await api.GET("/v1/orgs/{orgId}/customers/{customerId}", {
      params: { path: { orgId, customerId } },
    }),
  );
  if (client.id !== customerId) redirect(`${base}/${client.id}/merge`);

  const header = (
    <div className="flex flex-col gap-1">
      <Link href={`${base}/${client.id}`} className="bv-type-label text-fg-muted hover:text-fg">
        ‹ {client.name}
      </Link>
      <h1 className="bv-type-title1">{t("portal.clients.mergeTitle", { name: client.name })}</h1>
    </div>
  );

  if (query.with && query.with !== client.id) {
    const other = unwrap(
      await api.GET("/v1/orgs/{orgId}/customers/{customerId}", {
        params: { path: { orgId, customerId: query.with } },
      }),
    );
    if (other.id === client.id) redirect(`${base}/${client.id}`);
    return (
      <>
        {header}
        <div className="grid gap-6 sm:grid-cols-2">
          <Card title={t("portal.clients.keep")} client={client} language={language} />
          <Card title={t("portal.clients.mergeIn")} client={other} language={language} />
        </div>
        <div role="note" className="bv-type-body rounded-lg bg-warning-subtle px-4 py-3 text-fg">
          {t("portal.clients.mergeWarning", { name: client.name, other: other.name })}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <MergeForm
            language={language}
            orgId={orgId}
            customerId={client.id}
            duplicateId={other.id}
            label={t("portal.clients.mergeButton")}
          />
          <Link
            href={`${base}/${other.id}/merge?with=${client.id}`}
            className={secondaryButtonLargeClass}
          >
            {t("portal.clients.swap", { other: other.name })}
          </Link>
          <Link
            href={`${base}/${client.id}`}
            className="bv-type-label px-3 text-fg-muted hover:text-fg"
          >
            {t("portal.clients.cancel")}
          </Link>
        </div>
      </>
    );
  }

  // People with a similar name are suggested until the desk searches for someone else.
  const q = (query.q ?? client.name.split(" ")[0] ?? "").trim();
  const found = q
    ? unwrap(
        await api.GET("/v1/orgs/{orgId}/customers", {
          params: { path: { orgId }, query: { q } },
        }),
      ).items.filter((c) => c.id !== client.id)
    : [];
  return (
    <>
      {header}
      <p className="bv-type-body text-fg-muted">{t("portal.clients.mergeIntro")}</p>
      <form method="get" role="search" className="flex flex-wrap gap-2">
        <input
          type="search"
          name="q"
          defaultValue={q}
          aria-label={t("portal.clients.search")}
          placeholder={t("portal.clients.search")}
          className={`${inputBaseClass} min-w-0 flex-1 basis-56`}
        />
        <button className={secondaryButtonLargeClass}>{t("portal.clients.searchButton")}</button>
      </form>
      {found.length === 0 ? (
        <p className="bv-type-body text-fg-muted">{t("portal.clients.noMatch", { q })}</p>
      ) : (
        <ul className={listClass}>
          {found.map((c) => (
            <li key={c.id}>
              <Link
                href={`${base}/${client.id}/merge?with=${c.id}`}
                className="flex min-h-14 items-center justify-between gap-4 px-3 py-3 hover:bg-surface"
              >
                <span className="flex flex-col">
                  <span className="bv-type-body text-fg">{c.name}</span>
                  <span className="bv-type-caption text-fg-muted">
                    {[c.phone, t("portal.clients.visits", { count: c.visits })]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </span>
                <span className="bv-type-label text-primary">{t("portal.clients.choose")}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function Card({
  title,
  client,
  language,
}: {
  title: string;
  client: Schemas["Client"];
  language: Language;
}) {
  const t = translator(language);
  const zone = client.past[0]?.timezone ?? client.upcoming[0]?.timezone ?? "Africa/Addis_Ababa";
  const rows: [string, string][] = [
    [t("portal.clients.phone"), client.phone ?? "—"],
    [t("portal.clients.email"), client.email ?? "—"],
    [t("portal.clients.colVisits"), t("portal.clients.visits", { count: client.stats.visits })],
    [
      t("portal.clients.colLastVisit"),
      client.lastVisitAt
        ? dayOf(client.lastVisitAt, zone, language).primary
        : t("portal.clients.never"),
    ],
    [t("portal.clients.upcoming"), String(client.upcoming.length)],
    [t("portal.clients.notes"), String(client.notes.length)],
    [t("portal.clients.tags"), client.tags.join(language === "am" ? "፣ " : ", ") || "—"],
  ];
  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-col">
        <span className="bv-type-label text-fg-muted">{title}</span>
        <h2 className="bv-type-title2">{client.name}</h2>
      </div>
      <dl className={listClass}>
        {rows.map(([label, value]) => (
          <div key={label} className="flex justify-between gap-4 px-3 py-2">
            <dt className="bv-type-body text-fg-muted">{label}</dt>
            <dd className="bv-type-body text-end text-fg">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
