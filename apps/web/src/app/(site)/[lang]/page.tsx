import { connection } from "next/server";
import Link from "next/link";
import { pick, todayIn } from "@beavent/venue";
import { CategoryChips } from "@/components/discovery/CategoryChips";
import { Rail } from "@/components/discovery/Rail";
import { SearchBar } from "@/components/discovery/SearchBar";
import { VenueResultRow } from "@/components/discovery/VenueResultRow";
import { SiteHeader } from "@/components/SiteHeader";
import { loadCategories, loadCity, loadPlaces } from "@/lib/discovery";
import { routeLanguage, translator } from "@/lib/i18n";
import { searchHref } from "@/lib/search-filters";

/** The market's time zone, for "today". */
const MARKET_TIME_ZONE = "Africa/Addis_Ababa";
/** Below this many places the groups would only repeat each other: one list of everything instead (DD12). */
const MIN_PLACES_FOR_GROUPS = 8;
const MIN_PER_GROUP = 3;

/** Explore on the web (C-1): search, categories, and groups of places; a single list while the market is small. */
export default async function HomePage({ params }: PageProps<"/[lang]">) {
  await connection();
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  const today = todayIn(MARKET_TIME_ZONE);
  const [{ city, areas }, categories, all, availableToday, atHome, newest] = await Promise.all([
    loadCity(),
    loadCategories(),
    loadPlaces({}, { limit: 20 }),
    loadPlaces({ date: today }, { limit: 4 }),
    loadPlaces({ atHome: true }, { limit: 4 }),
    loadPlaces({ sort: "NEWEST" }, { limit: 4 }),
  ]);
  const cityName = pick(city.nameI18n ?? {}, language) || city.name;
  const total = all.total ?? all.items.length;
  const groups = total >= MIN_PLACES_FOR_GROUPS;
  const rails = [
    {
      title: t("home.availableToday"),
      page: availableToday,
      href: searchHref(language, { date: today }),
    },
    { title: t("home.atHome"), page: atHome, href: searchHref(language, { atHome: true }) },
    { title: t("home.newOnBeavent"), page: newest, href: searchHref(language, { sort: "NEWEST" }) },
  ].filter((r) => r.page.items.length >= MIN_PER_GROUP);

  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path="" />
      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-8 px-4 pb-16 sm:px-6">
        <section className="flex flex-col gap-4 pt-2">
          {/* Web marketing heading: 28 px on phones, 34 px (allowed only here, DESIGN_TOKENS.md §3.2) from 640 px. */}
          <h1 className="text-2xl font-semibold text-balance text-fg sm:text-3xl">
            {t("app.tagline")}
          </h1>
          <SearchBar language={language} filters={{}} city={city} areas={areas} />
        </section>

        {categories.length >= MIN_PER_GROUP ? (
          <section className="flex flex-col gap-3">
            <h2 className="bv-type-title2 text-fg">{t("home.browse")}</h2>
            <CategoryChips categories={categories} city={city} language={language} />
          </section>
        ) : null}

        {total === 0 ? (
          <section className="flex flex-col items-start gap-2 py-6">
            <h2 className="bv-type-title2 text-fg">{t("home.empty.title", { city: cityName })}</h2>
            <p className="bv-type-body max-w-prose text-fg-muted">{t("home.empty.body")}</p>
          </section>
        ) : groups && rails.length > 0 ? (
          rails.map((rail) => (
            <Rail
              key={rail.title}
              title={rail.title}
              items={rail.page.items}
              language={language}
              seeAllHref={rail.href}
            />
          ))
        ) : null}

        {total > 0 && (!groups || rails.length === 0) ? (
          <section className="flex flex-col gap-2">
            <h2 className="bv-type-title2 text-fg">{t("home.allPlaces", { city: cityName })}</h2>
            <ul className="grid gap-x-10 md:grid-cols-2">
              {all.items.map((item) => (
                <VenueResultRow key={item.id} item={item} language={language} />
              ))}
            </ul>
            {total > all.items.length ? (
              <Link
                href={searchHref(language, {})}
                className="bv-type-button mt-2 inline-flex min-h-12 items-center self-start rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus"
              >
                {t("home.seeAll")}
              </Link>
            ) : null}
          </section>
        ) : null}
      </main>
    </div>
  );
}
