import Link from "next/link";
import type { Language } from "@beavent/i18n";
import { pick } from "@beavent/venue";
import { landingHref, type AdminArea, type Category } from "@/lib/discovery";

/** Text-only category chips (no icons, P7) leading to each category's page in the city. */
export function CategoryChips({
  categories,
  city,
  language,
  current,
}: {
  categories: Category[];
  city: AdminArea;
  language: Language;
  current?: string;
}) {
  return (
    <ul className="flex flex-wrap gap-2">
      {categories.map((category) => (
        <li key={category.id}>
          <Link
            href={landingHref(language, city, category)}
            aria-current={category.code === current ? "page" : undefined}
            className={`bv-type-label inline-flex min-h-12 items-center rounded-full border px-4 hover:border-primary focus-visible:outline-2 focus-visible:outline-focus ${
              category.code === current
                ? "border-primary bg-primary-subtle text-fg"
                : "border-border-strong text-fg"
            }`}
          >
            {pick(category.name, language)}
          </Link>
        </li>
      ))}
    </ul>
  );
}
