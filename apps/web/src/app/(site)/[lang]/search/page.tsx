import type { Metadata } from "next";
import { connection } from "next/server";
import { FilterBar } from "@/components/discovery/FilterBar";
import { NoResults } from "@/components/discovery/NoResults";
import { ResultsList } from "@/components/discovery/ResultsList";
import { ResultsWithMap } from "@/components/discovery/ResultsWithMap";
import { SearchBar } from "@/components/discovery/SearchBar";
import { SiteHeader } from "@/components/SiteHeader";
import { loadCity, loadPlaces } from "@/lib/discovery";
import { routeLanguage, translator } from "@/lib/i18n";
import { parseFilters, toParams } from "@/lib/search-filters";
import { pick } from "@beavent/venue";

type Props = PageProps<"/[lang]/search">;

export async function generateMetadata({ params, searchParams }: Props): Promise<Metadata> {
  const t = translator(routeLanguage((await params).lang));
  const { q } = parseFilters(await searchParams);
  // Result pages are for people, not for search engines: the category pages are the indexable ones.
  return {
    title: q ? `${q} · ${t("search.title")}` : t("search.title"),
    robots: { index: false, follow: true },
  };
}

/** Search results (S-02): the search bar, sort and filters, and the places that match, by cursor. */
export default async function SearchPage({ params, searchParams }: Props) {
  await connection();
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  const filters = parseFilters(await searchParams);
  const [{ city, areas }, page] = await Promise.all([loadCity(), loadPlaces(filters)]);
  const query = toParams(filters).toString();
  const cityName = pick(city.nameI18n ?? {}, language) || city.name;

  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/search${query ? `?${query}` : ""}`} />
      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 pb-12 sm:px-6">
        <h1 className="bv-type-title1 text-fg">
          {filters.q ? `“${filters.q}”` : t("search.title")}
        </h1>
        <SearchBar language={language} filters={filters} city={city} areas={areas} compact />
        <FilterBar
          language={language}
          filters={filters}
          cityName={cityName}
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
          <NoResults language={language} filters={filters} />
        ) : (
          <ResultsWithMap language={language} filters={filters} scope={{}}>
            <ResultsList key={query} initial={page} language={language} query={query} scope={{}} />
          </ResultsWithMap>
        )}
      </main>
    </div>
  );
}
