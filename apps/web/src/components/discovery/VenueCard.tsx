import Link from "next/link";
import type { Language } from "@beavent/i18n";
import { nextAvailableText, resultHref, resultMeta, type SearchItem } from "@beavent/venue";
import { PlaceImage } from "@/components/discovery/PlaceImage";
import { SaveButton } from "@/components/discovery/SaveButton";
import { translator } from "@/lib/i18n";

/** A place in a grid on the home page (S-01 VenueCard): image-led and tappable, so a card (DD9). No border, no shadow. */
export function VenueCard({
  item,
  language,
  save,
  extra,
  action,
}: {
  item: SearchItem;
  language: Language;
  save?: { signedIn: boolean; saved: boolean };
  /** A line under the card's own (S-01 "Last: Box braids · with Hana"). */
  extra?: string;
  /** A button-like link under the card ("Book again"). */
  action?: { href: string; label: string };
}) {
  const t = translator(language);
  const next = nextAvailableText(item, language, t);
  return (
    <li className="relative">
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
        {extra ? <span className="bv-type-label text-fg-muted">{extra}</span> : null}
      </Link>
      {save && item.locationId ? (
        <SaveButton
          language={language}
          locationId={item.locationId}
          name={item.title}
          saved={save.saved}
          signedIn={save.signedIn}
          className="absolute end-1 top-1 rounded-full bg-background"
        />
      ) : null}
      {action ? (
        <Link
          href={action.href}
          className="bv-type-label mt-2 inline-flex min-h-12 items-center rounded-lg border border-border-strong px-4 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus"
        >
          {action.label}
        </Link>
      ) : null}
    </li>
  );
}
