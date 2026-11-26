import Link from "next/link";
import { Section, formatPrice, listClass, toBirr } from "@/components/business/ui";
import { portalApi, unwrap } from "@/lib/business/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { AddProductForm, ArchiveButton, CorrectForm, PriceForm, ReceiveForm } from "./ProductForms";

/** Retail products and their stock at a location (plan 4.5). */
export default async function ProductsPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/products">) {
  const { lang, orgId } = await params;
  const query = (await searchParams) as { location?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId } } }),
  ).items;
  const location = locations.find((l) => l.id === query.location) ?? locations[0];
  const products = unwrap(
    await api.GET("/v1/orgs/{orgId}/products", {
      params: { path: { orgId }, query: { locationId: location?.id, includeArchived: true } },
    }),
  ).items;
  const base = `/${language}/business/o/${orgId}`;
  const nameOf = (n: Record<string, string>) => n[language] ?? n.en ?? Object.values(n)[0] ?? "";
  const labels = Object.fromEntries(
    [
      "nameEn",
      "nameAm",
      "brand",
      "variant",
      "sku",
      "price",
      "cost",
      "track",
      "save",
      "setPrice",
    ].map((k) => [
      k,
      t(
        k === "nameEn"
          ? "products.name.en"
          : k === "nameAm"
            ? "products.name.am"
            : (`products.${k}` as never),
      ),
    ]),
  );
  const stockLabels = {
    quantity: t("products.stock.quantity"),
    reason: t("products.stock.reason"),
    change: t("products.stock.change"),
    kind: t("products.stock.kind"),
    ADJUSTMENT: t("products.stock.kind.ADJUSTMENT"),
    DAMAGE: t("products.stock.kind.DAMAGE"),
    save: t("products.stock.save"),
  };

  return (
    <>
      <h1 className="bv-type-title1">{t("products.title")}</h1>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("products.intro")}</p>
      {locations.length > 1 && (
        <nav className="flex flex-wrap gap-2">
          {locations.map((l) => (
            <Link
              key={l.id}
              href={`${base}/products?location=${l.id}`}
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

      {products.length === 0 ? (
        <p role="status" className="bv-type-body text-fg-muted">
          {t("products.empty")}
        </p>
      ) : (
        <ul className={listClass}>
          {products.map((p) => (
            <li key={p.id} className="flex flex-col gap-3 px-3 py-4">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="bv-type-body font-medium">
                  {nameOf(p.name)}
                  {p.brand ? <span className="text-fg-muted"> · {p.brand}</span> : null}
                </span>
                {p.status === "ARCHIVED" ? (
                  <span className="bv-type-caption text-fg-muted">{t("products.archived")}</span>
                ) : null}
              </div>
              {p.variants
                .filter((v) => v.status === "ACTIVE")
                .map((v) => (
                  <div key={v.id} className="flex flex-col gap-2 border-t border-border pt-3">
                    <div className="flex flex-wrap items-baseline justify-between gap-2">
                      <span className="bv-type-body">
                        {v.label}
                        {v.sku ? (
                          <span className="text-fg-muted">
                            {v.label ? " · " : ""}
                            {v.sku}
                          </span>
                        ) : null}
                      </span>
                      <span className="bv-type-body tabular-nums">
                        {formatPrice(v.priceMinor, v.currency, language)}
                      </span>
                    </div>
                    <span className="bv-type-caption text-fg-muted">
                      {p.trackInventory
                        ? t("products.onHand", { count: String(v.onHand ?? 0) })
                        : t("products.notTracked")}
                    </span>
                    {p.status === "ACTIVE" ? (
                      <div className="flex flex-wrap gap-4">
                        <details>
                          <summary className="bv-type-label cursor-pointer underline">
                            {t("products.setPrice")}
                          </summary>
                          <div className="pt-3">
                            <PriceForm
                              language={language}
                              orgId={orgId}
                              productId={p.id}
                              variantId={v.id}
                              current={toBirr(v.priceMinor, v.currency)}
                              labels={labels}
                            />
                          </div>
                        </details>
                        {p.trackInventory && location ? (
                          <>
                            <details>
                              <summary className="bv-type-label cursor-pointer underline">
                                {t("products.stock.receive")}
                              </summary>
                              <div className="pt-3">
                                <ReceiveForm
                                  language={language}
                                  orgId={orgId}
                                  locationId={location.id}
                                  variantId={v.id}
                                  labels={stockLabels}
                                />
                              </div>
                            </details>
                            <details>
                              <summary className="bv-type-label cursor-pointer underline">
                                {t("products.stock.correct")}
                              </summary>
                              <div className="pt-3">
                                <CorrectForm
                                  language={language}
                                  orgId={orgId}
                                  locationId={location.id}
                                  variantId={v.id}
                                  labels={stockLabels}
                                />
                              </div>
                            </details>
                          </>
                        ) : null}
                      </div>
                    ) : null}
                  </div>
                ))}
              {p.status === "ACTIVE" ? (
                <ArchiveButton
                  language={language}
                  orgId={orgId}
                  productId={p.id}
                  label={t("products.archive")}
                />
              ) : null}
            </li>
          ))}
        </ul>
      )}

      <Section title={t("products.add")}>
        <AddProductForm language={language} orgId={orgId} labels={labels} />
      </Section>
    </>
  );
}
