import type { Venue } from "@beavent/venue";
import type { Language } from "@beavent/i18n";

/** People you can book (S-04 "Team"): a photo or initial, name and job title. At least three make a rail (DD12). */
export function TeamRail({ venue }: { venue: Venue; language?: Language }) {
  return (
    <ul className="flex gap-6 overflow-x-auto pb-1">
      {venue.team.map((person) => (
        <li key={person.id} className="flex w-24 shrink-0 flex-col items-center gap-2 text-center">
          {person.photo ? (
            // eslint-disable-next-line @next/next/no-img-element -- public CDN images already resized by the files pipeline
            <img
              src={person.photo.variants.w320 ?? person.photo.url}
              alt=""
              className="size-14 rounded-full bg-surface object-cover"
            />
          ) : (
            <span
              aria-hidden="true"
              className="bv-type-title2 flex size-14 items-center justify-center rounded-full bg-surface text-fg-muted"
            >
              {person.displayName.slice(0, 1)}
            </span>
          )}
          <span className="flex flex-col">
            <span className="bv-type-label text-fg">{person.displayName}</span>
            {person.jobTitle ? (
              <span className="bv-type-caption text-fg-muted">{person.jobTitle}</span>
            ) : null}
          </span>
        </li>
      ))}
    </ul>
  );
}
