import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { pick, slugOf } from "@beavent/venue";
import { CategoryChips } from "@/components/discovery/CategoryChips";
import { FilterBar } from "@/components/discovery/FilterBar";
import { NoResults } from "@/components/discovery/NoResults";
import { ResultsList } from "@/components/discovery/ResultsList";
import { SiteHeader } from "@/components/SiteHeader";
import {
  categoryBySlug,
  loadAllCategories,
  loadCategories,
  loadCity,
  loadPlaces,
} from "@/lib/discovery";
import { routeLanguage, translator } from "@/lib/i18n";
import { parseFilters, toParams } from "@/lib/search-filters";

type Props = PageProps<"/[lang]/[city]/[category]">;

async function resolve(citySlug: string, categorySlug: string) {
  const [{ city, areas }, categories] = await Promise.all([loadCity(), loadAllCategories()]);
  const category = categoryBySlug(categories, categorySlug);
  if (slugOf(city.name) !== citySlug || !category) return null;
  return { city, areas, category };
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { lang, city: citySlug, category: categorySlug } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const found = await resolve(citySlug, categorySlug).catch(() => null);
  if (!found) return { robots: { index: false } };
  const names = {
    category: pick(found.category.name, language),
    city: pick(found.city.nameI18n ?? {}, language) || found.city.name,
  };
  const count = await loadPlaces({}, { category: found.category.code, limit: 1 })
    .then((p) => p.total ?? p.items.length)
    .catch(() => 0);
  return {
    title: t("landing.title", names),
    description: t("landing.intro", names),
    // A page with nothing on it is not worth indexing yet.
    robots: count > 0 ? undefined : { index: false, follow: true },
    alternates: {
      languages: {
        am: `/am/${citySlug}/${categorySlug}`,
        en: `/en/${citySlug}/${categorySlug}`,
      },
    },
  };
}

/** A category in the city, for people arriving from a search engine (S-02 category landing): intro, filters, places. */
export default async function LandingPage({ params, searchParams }: Props) {
  await connection();
  const { lang, city: citySlug, category: categorySlug } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const found = await resolve(citySlug, categorySlug);
  if (!found) notFound();
  const { city, areas, category } = found;
  const filters = parseFilters(await searchParams);
  const [page, categories] = await Promise.all([
    loadPlaces(filters, { category: category.code }),
    loadCategories(),
  ]);
  const names = {
    category: pick(category.name, language),
    city: pick(city.nameI18n ?? {}, language) || city.name,
  };
  const query = toParams(filters).toString();
  const here = `/${language}/${citySlug}/${categorySlug}`;

  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader
        language={language}
        path={`/${citySlug}/${categorySlug}${query ? `?${query}` : ""}`}
      />
      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 pb-12 sm:px-6">
        <header className="flex flex-col gap-2">
          <h1 className="bv-type-display text-fg">{t("landing.title", names)}</h1>
          <p className="bv-type-body max-w-prose text-fg-muted">{t("landing.intro", names)}</p>
        </header>
        <FilterBar
          language={language}
          filters={filters}
          cityName={names.city}
          areas={areas.map((a) => ({
            code: a.code ?? a.id,
            nameI18n: a.nameI18n ?? {},
            name: a.name,
          }))}
        />
        <p aria-live="polite" className="bv-type-label text-fg-muted">
          {t("results.count", { count: page.total ?? page.items.length })}
        </p>
        {page.items.length === 0 ? (
          <NoResults
            language={language}
            filters={filters}
            base={(f) => {
              const q = toParams(f).toString();
              return q ? `${here}?${q}` : here;
            }}
            title={t("landing.empty.title")}
            body={t("landing.empty.body", { city: names.city })}
          />
        ) : (
          <ResultsList
            key={query}
            initial={page}
            language={language}
            query={query}
            scope={{ category: category.code }}
          />
        )}
        <section className="flex flex-col gap-3 border-t border-border pt-6">
          <h2 className="bv-type-title2 text-fg">{t("landing.more", { city: names.city })}</h2>
          <CategoryChips
            categories={categories}
            city={city}
            language={language}
            current={category.code}
          />
        </section>
      </main>
    </div>
  );
}
