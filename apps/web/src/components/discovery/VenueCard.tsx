import Link from "next/link";
import type { Language } from "@beavent/i18n";
import { nextAvailableText, resultHref, resultMeta, type SearchItem } from "@beavent/venue";
import { PlaceImage } from "@/components/discovery/PlaceImage";
import { translator } from "@/lib/i18n";

/** A place in a grid on the home page (S-01 VenueCard): image-led and tappable, so a card (DD9). No border, no shadow. */
export function VenueCard({ item, language }: { item: SearchItem; language: Language }) {
  const t = translator(language);
  const next = nextAvailableText(item, language, t);
  return (
    <li>
      <Link
        href={`/${language}${resultHref(item)}`}
        className="group flex flex-col gap-2 rounded-lg focus-visible:outline-2 focus-visible:outline-focus"
      >
        <PlaceImage
          url={item.imageUrl}
          variants={item.imageVariants}
          name={item.title}
          size="w960"
          className="aspect-[3/2] w-full rounded-lg"
        />
        <span className="bv-type-body line-clamp-2 font-semibold text-fg group-hover:text-primary">
          {item.title}
        </span>
        <span className="bv-type-label text-fg-muted">{resultMeta(item, language, t)}</span>
        {next ? <span className="bv-type-label text-fg">{next}</span> : null}
      </Link>
    </li>
  );
}
