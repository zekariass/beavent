import {
  durationText,
  pick,
  price,
  priceText,
  serviceFacts,
  type Venue,
  type VenueService,
} from "@beavent/venue";
import { translator } from "@/lib/i18n";
import { formatDuration, type Language } from "@beavent/i18n";

/**
 * The menu (S-04 "Services"): group chips, group headers and one row per service with a disclosure for the details
 * (S-05 becomes a sheet with a basket in step 2.4). Rows are separated by dividers, not cards (DD9).
 */
export function ServiceList({ venue, language }: { venue: Venue; language: Language }) {
  const t = translator(language);
  if (venue.groups.length === 0) {
    return <p className="text-fg-muted">{t("venue.noServices")}</p>;
  }
  const named = venue.groups.filter((g) => g.id);
  return (
    <div className="flex flex-col gap-6">
      {named.length > 1 ? (
        <nav
          aria-label={t("venue.services")}
          className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1"
        >
          {named.map((g) => (
            <a
              key={g.id}
              href={`#group-${g.id}`}
              className="bv-type-label inline-flex min-h-12 shrink-0 items-center rounded-full border border-border-strong px-4 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus"
            >
              {pick(g.name, language)}
            </a>
          ))}
        </nav>
      ) : null}
      {venue.groups.map((group) => (
        <section
          key={group.id ?? "other"}
          id={group.id ? `group-${group.id}` : undefined}
          className="scroll-mt-28"
        >
          {group.id ? (
            <h3 className="bv-type-body-strong mb-2 text-fg">{pick(group.name, language)}</h3>
          ) : named.length > 0 ? (
            <h3 className="bv-type-body-strong mb-2 text-fg">{t("venue.otherServices")}</h3>
          ) : null}
          <ul className="divide-y divide-border border-y border-border">
            {group.services.map((service) => (
              <li key={service.id}>
                <ServiceRow service={service} language={language} />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

function ServiceRow({ service, language }: { service: VenueService; language: Language }) {
  const t = translator(language);
  const extra = serviceFacts(service, t);
  const description = pick(service.description, language);
  const aftercare = pick(service.aftercare, language);
  const several = service.variants.length > 1;
  return (
    <details className="group">
      <summary className="flex min-h-14 cursor-pointer list-none items-center justify-between gap-4 py-3 focus-visible:outline-2 focus-visible:outline-focus [&::-webkit-details-marker]:hidden">
        <span className="flex min-w-0 flex-col">
          <span className="bv-type-body text-fg">{pick(service.name, language)}</span>
          <span className="bv-type-caption text-fg-muted">
            {[durationText(service.variants, t), ...extra].join(" · ")}
          </span>
        </span>
        <span className="bv-type-body-strong shrink-0 text-fg">
          {priceText(service.variants, language, t)}
        </span>
      </summary>
      <div className="flex flex-col gap-4 pb-4">
        {description ? <p className="bv-type-body text-fg-muted">{description}</p> : null}
        {service.requiresPatchTest ? (
          <p className="bv-type-label text-fg">{t("venue.patchTest")}</p>
        ) : null}
        {several ? (
          <ul className="divide-y divide-border">
            {service.variants.map((v) => (
              <li key={v.id} className="flex items-center justify-between gap-4 py-3">
                <span className="flex flex-col">
                  <span className="bv-type-body text-fg">{pick(v.name, language)}</span>
                  <span className="bv-type-caption text-fg-muted">
                    {formatDuration(v.durationMinutes, t)}
                  </span>
                </span>
                <span className="bv-type-body-strong text-fg">{priceText([v], language, t)}</span>
              </li>
            ))}
          </ul>
        ) : null}
        {service.addOns.length > 0 ? (
          <div className="flex flex-col gap-2">
            <p className="bv-type-label text-fg-muted">{t("venue.addons")}</p>
            <ul className="divide-y divide-border">
              {service.addOns.map((a) => (
                <li key={a.id} className="flex items-center justify-between gap-4 py-3">
                  <span className="bv-type-body text-fg">{pick(a.name, language)}</span>
                  <span className="bv-type-caption text-fg-muted">
                    {a.durationMinutes > 0 ? `+ ${formatDuration(a.durationMinutes, t)} · ` : ""}+{" "}
                    {price(a.priceMinor, a.currency, language)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        {aftercare ? (
          <div className="flex flex-col gap-1">
            <p className="bv-type-label text-fg-muted">{t("venue.aftercare")}</p>
            <p className="bv-type-body text-fg">{aftercare}</p>
          </div>
        ) : null}
      </div>
    </details>
  );
}
