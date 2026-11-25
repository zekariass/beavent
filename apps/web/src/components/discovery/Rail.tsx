import Link from "next/link";
import type { Language } from "@beavent/i18n";
import type { SearchItem } from "@beavent/venue";
import { VenueCard } from "@/components/discovery/VenueCard";
import { translator } from "@/lib/i18n";

/** A titled group of places on the home page: a grid of at most four, with "See all" (DD12: shown only with ≥ 3). */
export function Rail({
  title,
  items,
  language,
  seeAllHref,
  save,
}: {
  title: string;
  items: SearchItem[];
  language: Language;
  seeAllHref: string;
  save?: { signedIn: boolean; ids: Set<string> };
}) {
  const t = translator(language);
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between gap-4">
        <h2 className="bv-type-title2 text-fg">{title}</h2>
        <Link
          href={seeAllHref}
          className="bv-type-label inline-flex min-h-12 items-center text-primary hover:text-primary-pressed focus-visible:outline-2 focus-visible:outline-focus"
        >
          {t("home.seeAll")}
        </Link>
      </div>
      <ul className="grid grid-cols-2 gap-x-4 gap-y-6 lg:grid-cols-4">
        {items.slice(0, 4).map((item) => (
          <VenueCard
            key={item.id}
            item={item}
            language={language}
            save={
              save
                ? { signedIn: save.signedIn, saved: save.ids.has(item.locationId ?? "") }
                : undefined
            }
          />
        ))}
      </ul>
    </section>
  );
}
