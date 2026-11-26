import Link from "next/link";
import type { Language } from "@beavent/i18n";
import {
  menuLinePrice,
  nextAvailableText,
  pick,
  resultHref,
  resultMeta,
  resultPrice,
  type SearchItem,
} from "@beavent/venue";
import { formatDuration } from "@beavent/i18n";
import { PlaceImage } from "@/components/discovery/PlaceImage";
import { RatingInline } from "@/components/reviews/Stars";
import { SaveButton } from "@/components/discovery/SaveButton";
import { translator } from "@/lib/i18n";

/**
 * One place in a result list (S-02 VenueResultRow): photo, name, area and distance, the next free time, and up to three
 * services that match what was searched, each leading straight into booking it. Rows with dividers, not cards (DD9).
 */
export function VenueResultRow({
  item,
  language,
  save,
}: {
  item: SearchItem;
  language: Language;
  /** Shows the heart: whether the person is signed in and has saved this place. */
  save?: { signedIn: boolean; saved: boolean };
}) {
  const t = translator(language);
  const href = `/${language}${resultHref(item)}`;
  const next = nextAvailableText(item, language, t);
  const price = item.menu.length === 0 ? resultPrice(item, language, t) : null;
  const more = item.serviceCount - item.menu.length;
  const focus = "focus-visible:outline-2 focus-visible:outline-focus";
  return (
    <li className="flex gap-4 border-b border-border py-4">
      <Link href={href} tabIndex={-1} aria-hidden="true" className="shrink-0">
        <PlaceImage
          url={item.imageUrl}
          variants={item.imageVariants}
          name={item.title}
          className="size-[104px] rounded-lg"
        />
      </Link>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-start justify-between gap-2">
          <Link
            href={href}
            className={`bv-type-body line-clamp-2 min-h-6 font-semibold text-fg hover:text-primary ${focus}`}
          >
            {item.title}
          </Link>
          {save && item.locationId ? (
            <SaveButton
              language={language}
              locationId={item.locationId}
              name={item.title}
              saved={save.saved}
              signedIn={save.signedIn}
              className="relative -mt-3 -me-3 shrink-0"
            />
          ) : null}
        </div>
        <p className="bv-type-label text-fg-muted">{resultMeta(item, language, t)}</p>
        <RatingInline average={item.ratingAvg} count={item.ratingCount} language={language} />
        {item.landmark ? <p className="bv-type-caption text-fg-muted">{item.landmark}</p> : null}
        {next ? <p className="bv-type-label text-fg">{next}</p> : null}
        {price ? <p className="bv-type-label text-fg">{price}</p> : null}
        {item.menu.length > 0 ? (
          <ul className="mt-1 flex flex-col">
            {item.menu.map((line) => (
              <li key={line.serviceId}>
                <Link
                  href={`/${language}/b/${item.businessSlug}/${item.locationSlug}/book?service=${line.serviceId}`}
                  className={`flex min-h-12 items-center justify-between gap-3 rounded-md py-1 hover:text-primary ${focus}`}
                >
                  <span className="flex min-w-0 flex-col">
                    <span className="bv-type-label text-fg">{pick(line.name, language)}</span>
                    <span className="bv-type-caption text-fg-muted">
                      {formatDuration(line.durationMinutes, t)}
                    </span>
                  </span>
                  <span className="bv-type-label shrink-0 font-medium text-fg">
                    {menuLinePrice(line, item.currency ?? "ETB", language, t)}
                  </span>
                </Link>
              </li>
            ))}
            {more > 0 ? (
              <li>
                <Link
                  href={`${href}#services`}
                  className={`bv-type-label inline-flex min-h-12 items-center text-primary hover:text-primary-pressed ${focus}`}
                >
                  {t("results.seeAllServices", { count: item.serviceCount })}
                </Link>
              </li>
            ) : null}
          </ul>
        ) : null}
      </div>
    </li>
  );
}
