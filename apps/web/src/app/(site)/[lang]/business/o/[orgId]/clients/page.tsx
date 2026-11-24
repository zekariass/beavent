import Link from "next/link";
import { buttonClass, inputBaseClass, secondaryButtonLargeClass } from "@/components/business/ui";
import { dayOf } from "@/components/business/when";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";

/**
 * Clients (P-13): everyone the business serves, by name (Fresha's client list). Search by name or phone, filter by
 * tag; a row opens the client. Add one by hand or import a list.
 */
export default async function ClientsPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/clients">) {
  const { lang, orgId } = await params;
  const query = (await searchParams) as { q?: string; tag?: string; cursor?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const q = (query.q ?? "").trim();
  const [page, tags, locations] = await Promise.all([
    api
      .GET("/v1/orgs/{orgId}/customers", {
        params: {
          path: { orgId },
          query: {
            q: q || undefined,
            tagId: query.tag || undefined,
            cursor: query.cursor || undefined,
            limit: 50,
          },
        },
      })
      .then(unwrap),
    api.GET("/v1/orgs/{orgId}/customer-tags", { params: { path: { orgId } } }).then(unwrap),
    api
      .GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId }, query: { limit: 1 } } })
      .then(unwrap),
  ]);
  const zone = locations.items[0]?.timezone ?? "Africa/Addis_Ababa";
  const base = `/${language}/business/o/${orgId}/clients`;
  const filtered = q !== "" || Boolean(query.tag);
  const grid =
    "grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 px-3 md:grid-cols-[minmax(0,2fr)_minmax(0,1.3fr)_minmax(0,1.3fr)_4rem_minmax(0,1.4fr)]";

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="bv-type-title1">{t("portal.clients.title")}</h1>
        <div className="flex flex-wrap gap-2">
          <Link href={`${base}/import`} className={secondaryButtonLargeClass}>
            {t("portal.clients.import")}
          </Link>
          <Link href={`${base}/new`} className={buttonClass}>
            {t("portal.clients.add")}
          </Link>
        </div>
      </div>

      <form method="get" role="search" className="flex flex-wrap gap-2">
        <input
          type="search"
          name="q"
          defaultValue={q}
          placeholder={t("portal.clients.search")}
          aria-label={t("portal.clients.search")}
          className={`${inputBaseClass} min-w-0 flex-1 basis-56`}
        />
        {tags.items.length > 0 ? (
          <select
            name="tag"
            defaultValue={query.tag ?? ""}
            aria-label={t("portal.clients.colTags")}
            className={`${inputBaseClass} basis-40`}
          >
            <option value="">{t("portal.clients.allTags")}</option>
            {tags.items.map((tag) => (
              <option key={tag.id} value={tag.id}>
                {tag.name} ({tag.clients})
              </option>
            ))}
          </select>
        ) : null}
        <button className={secondaryButtonLargeClass}>{t("portal.clients.searchButton")}</button>
      </form>

      {page.items.length === 0 ? (
        <div className="flex flex-col items-start gap-4 py-8">
          <p className="bv-type-body text-fg-muted">
            {filtered ? t("portal.clients.noMatch", { q }) : t("portal.clients.empty")}
          </p>
          {!filtered ? (
            <Link href={`${base}/new`} className={buttonClass}>
              {t("portal.clients.add")}
            </Link>
          ) : null}
        </div>
      ) : (
        <div>
          <div
            className={`${grid} bv-type-label hidden border-b border-border py-2 text-fg-muted md:grid`}
          >
            <span>{t("portal.clients.colName")}</span>
            <span>{t("portal.clients.colPhone")}</span>
            <span>{t("portal.clients.colLastVisit")}</span>
            <span className="text-end">{t("portal.clients.colVisits")}</span>
            <span>{t("portal.clients.colTags")}</span>
          </div>
          <ul className="divide-y divide-border border-b border-border">
            {page.items.map((c) => {
              const last = c.lastVisitAt
                ? dayOf(c.lastVisitAt, zone, language).primary
                : t("portal.clients.never");
              return (
                <li key={c.id}>
                  <Link
                    href={`${base}/${c.id}`}
                    className={`${grid} min-h-14 py-3 hover:bg-surface`}
                  >
                    <span className="flex min-w-0 flex-col">
                      <span className="bv-type-body truncate text-fg">{c.name}</span>
                      {c.alerts > 0 ? (
                        <span className="bv-type-caption flex items-center gap-1.5 text-fg-muted">
                          <span aria-hidden className="size-2 rounded-full bg-warning" />
                          {t("portal.clients.hasAlert")}
                        </span>
                      ) : null}
                      <span className="bv-type-caption text-fg-muted md:hidden">
                        {[c.phone, last].filter(Boolean).join(" · ")}
                      </span>
                    </span>
                    <span className="bv-type-body hidden truncate text-fg-muted md:block">
                      {c.phone ?? ""}
                    </span>
                    <span className="bv-type-body hidden truncate text-fg-muted md:block">
                      {last}
                    </span>
                    <span className="bv-type-body text-end text-fg tabular-nums">{c.visits}</span>
                    <span className="bv-type-caption hidden truncate text-fg-muted md:block">
                      {c.tags.join(language === "am" ? "፣ " : ", ")}
                    </span>
                  </Link>
                </li>
              );
            })}
          </ul>
          {page.nextCursor ? (
            <Link
              href={`${base}?${new URLSearchParams({
                ...(query.tag ? { tag: query.tag } : {}),
                cursor: page.nextCursor,
              })}`}
              className={`${secondaryButtonLargeClass} mt-4`}
            >
              {t("portal.clients.more")}
            </Link>
          ) : null}
        </div>
      )}
    </>
  );
}
