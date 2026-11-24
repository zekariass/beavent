import type { Venue } from "@beavent/venue";
import { translator } from "@/lib/i18n";
import type { Language } from "@beavent/i18n";

/**
 * Hero photos (S-04): one 3:2 image on phones, a mosaic of up to three from 640 px. Without photos the business's
 * initial on a plain surface: no stock imagery.
 */
export function VenueGallery({ venue, language }: { venue: Venue; language: Language }) {
  const t = translator(language);
  const photos = venue.gallery.slice(0, 3);
  if (photos.length === 0) {
    return (
      <div
        aria-hidden="true"
        className="bv-type-display flex aspect-[3/2] w-full items-center justify-center rounded-lg bg-surface text-fg-subtle sm:aspect-auto sm:h-48"
      >
        {venue.businessName.slice(0, 1)}
      </div>
    );
  }
  const total = venue.gallery.length;
  const alt = (index: number) =>
    t("venue.photoAlt", { n: index + 1, total, name: venue.businessName });
  const image = (index: number, className: string) => {
    const photo = photos[index]!;
    const small = photo.variants.w320;
    const medium = photo.variants.w960 ?? photo.url;
    return (
      // eslint-disable-next-line @next/next/no-img-element -- public CDN images already resized by the files pipeline
      <img
        src={medium}
        srcSet={small ? `${small} 320w, ${medium} 960w` : undefined}
        sizes={index === 0 ? "(min-width: 640px) 66vw, 100vw" : "33vw"}
        alt={photo.alt[language] ?? alt(index)}
        className={`h-full w-full object-cover ${className}`}
        loading={index === 0 ? "eager" : "lazy"}
      />
    );
  };
  return (
    <div className="grid grid-cols-3 grid-rows-2 gap-2 overflow-hidden rounded-lg sm:aspect-[3/1]">
      <div
        className={`col-span-3 aspect-[3/2] bg-surface sm:aspect-auto ${photos.length > 1 ? "sm:col-span-2 sm:row-span-2" : "sm:col-span-3 sm:row-span-2"} row-span-2`}
      >
        {image(0, "")}
      </div>
      {photos.slice(1).map((_, i) => (
        <div key={i} className="hidden bg-surface sm:block">
          {image(i + 1, "")}
        </div>
      ))}
    </div>
  );
}
