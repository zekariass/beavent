import Link from "next/link";
import { portalApi, unwrap } from "@/lib/business/session";
import { sellables } from "@/lib/business/products";
import { routeLanguage, translator } from "@/lib/i18n";
import { SellForm } from "./SellForm";

/** Selling goods at the counter with no visit (plan 4.5). */
export default async function SellPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/sell">) {
  const { lang, orgId } = await params;
  const query = (await searchParams) as { location?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId } } }),
  ).items;
  const location = locations.find((l) => l.id === query.location) ?? locations[0];
  const items = location
    ? sellables(
        unwrap(
          await api.GET("/v1/orgs/{orgId}/products", {
            params: { path: { orgId }, query: { locationId: location.id } },
          }),
        ).items,
        language,
      ).filter((i) => i.onHand === null || i.onHand > 0)
    : [];
  const base = `/${language}/business/o/${orgId}`;
  return (
    <>
      <h1 className="bv-type-title1">{t("sell.title")}</h1>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("sell.intro")}</p>
      {locations.length > 1 && (
        <nav className="flex flex-wrap gap-2">
          {locations.map((l) => (
            <Link
              key={l.id}
              href={`${base}/sell?location=${l.id}`}
              aria-current={l.id === location?.id ? "page" : undefined}
              className={`bv-type-label inline-flex min-h-11 items-center rounded-lg border px-4 ${
                l.id === location?.id ? "border-primary bg-primary-subtle" : "border-border-strong"
              }`}
            >
              {l.name}
            </Link>
          ))}
        </nav>
      )}
      {location && items.length > 0 ? (
        <SellForm language={language} orgId={orgId} locationId={location.id} items={items} />
      ) : (
        <p role="status" className="bv-type-body text-fg-muted">
          {t("sell.none")}
        </p>
      )}
    </>
  );
}
