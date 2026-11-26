"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type { Map as MapLibreMap, Marker, StyleSpecification } from "maplibre-gl";
import type { Language } from "@beavent/i18n";
import { price, type SearchPin, type SearchPins } from "@beavent/venue";
import { pinsFor } from "@/app/(site)/[lang]/search/actions";
import { translator } from "@/lib/i18n";
import { toParams, withFilters, type Filters } from "@/lib/search-filters";

/**
 * Where the map's tiles come from. Set `NEXT_PUBLIC_MAP_STYLE_URL` to a MapLibre style (the chosen provider's, with
 * its key). Without it, OpenStreetMap's public tiles are used, which is for development only: their usage policy
 * does not allow production traffic (decision G8).
 */
const STYLE: string | StyleSpecification = process.env.NEXT_PUBLIC_MAP_STYLE_URL ?? {
  version: 8,
  sources: {
    osm: {
      type: "raster",
      tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      tileSize: 256,
      maxzoom: 19,
      attribution: "© OpenStreetMap contributors",
    },
  },
  layers: [{ id: "osm", type: "raster", source: "osm" }],
};

const ADDIS: [number, number] = [38.7578, 9.0108];

const PIN =
  "bv-type-label min-h-10 min-w-10 cursor-pointer rounded-full border border-border-strong bg-background px-3 font-semibold text-fg hover:border-primary focus-visible:outline-2 focus-visible:outline-focus";
const PIN_SELECTED =
  "bv-type-label min-h-10 min-w-10 cursor-pointer rounded-full border border-primary bg-primary px-3 font-semibold text-on-primary focus-visible:outline-2 focus-visible:outline-focus";

/**
 * The places of a search on a map (S-03): a pill with the "from" price for each place, a card for the chosen one, and
 * "Search this area" once the map was moved, which limits the list to what the map shows. The list beside it is the
 * accessible way to the same places; pins are buttons too.
 */
export function ResultsMap({
  language,
  filters,
  scope,
}: {
  language: Language;
  filters: Filters;
  scope: { category?: string; area?: string };
}) {
  const t = translator(language);
  const router = useRouter();
  const pathname = usePathname();
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markers = useRef<Marker[]>([]);
  const fitted = useRef(false);
  const [ready, setReady] = useState(false);
  const [moved, setMoved] = useState(false);
  const [pins, setPins] = useState<SearchPins | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const query = toParams(filters).toString();
  const scopeKey = `${scope.category ?? ""}/${scope.area ?? ""}`;

  // The map: created once, in the browser only.
  useEffect(() => {
    let cancelled = false;
    let map: MapLibreMap | undefined;
    let observer: ResizeObserver | undefined;
    import("maplibre-gl").then(({ Map, NavigationControl, setWorkerUrl }) => {
      if (cancelled || !container.current) return;
      setWorkerUrl("/maplibre/maplibre-gl-worker.mjs"); // copied by scripts/copy-map-worker.mjs
      map = new Map({
        container: container.current,
        style: STYLE,
        center: ADDIS,
        zoom: 11,
        attributionControl: { compact: true },
      });
      map.addControl(new NavigationControl({ showCompass: false }), "top-right");
      map.on("moveend", (event) => {
        // Only a person moving the map counts, not the map fitting itself to the pins.
        if ((event as { originalEvent?: unknown }).originalEvent) setMoved(true);
      });
      mapRef.current = map;
      observer = new ResizeObserver(() => map?.resize());
      observer.observe(container.current);
      setReady(true);
    });
    return () => {
      cancelled = true;
      observer?.disconnect();
      markers.current.forEach((m) => m.remove());
      markers.current = [];
      map?.remove();
      mapRef.current = null;
    };
  }, []);

  // The pins of this search.
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    pinsFor(query, scope)
      .then((result) => !cancelled && setPins(result))
      .catch(() => !cancelled && setPins({ pins: [], truncated: false }));
    return () => {
      cancelled = true;
    };
    // `scopeKey` stands for the scope's content.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, query, scopeKey]);

  // Draw them; the first time, show all of them.
  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map || !pins) return;
    markers.current.forEach((m) => m.remove());
    markers.current = [];
    import("maplibre-gl").then(({ Marker, LngLatBounds }) => {
      pins.pins.forEach((pin) => {
        const label = pinLabel(pin, language);
        const element = document.createElement("button");
        element.type = "button";
        element.textContent = label;
        element.setAttribute("aria-label", `${pin.title}${label ? `, ${label}` : ""}`);
        element.className = pin.id === selected ? PIN_SELECTED : PIN;
        element.addEventListener("click", () => setSelected(pin.id));
        markers.current.push(
          new Marker({ element }).setLngLat([pin.longitude, pin.latitude]).addTo(map),
        );
      });
      if (!fitted.current && pins.pins.length > 0) {
        fitted.current = true;
        const bounds = new LngLatBounds();
        pins.pins.forEach((p) => bounds.extend([p.longitude, p.latitude]));
        map.fitBounds(bounds, { padding: 70, maxZoom: 15, duration: 0 });
      }
    });
  }, [ready, pins, selected, language]);

  const searchHere = () => {
    const bounds = mapRef.current?.getBounds();
    if (!bounds) return;
    const bbox = [bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()]
      .map((n) => Math.round(n * 1e5) / 1e5)
      .join(",");
    setMoved(false);
    setSelected(null);
    const next = toParams(withFilters(filters, { bbox })).toString();
    router.replace(`${pathname}?${next}`, { scroll: false });
  };

  const chosen = pins?.pins.find((p) => p.id === selected);
  return (
    <div
      role="region"
      aria-label={t("map.label")}
      className="bv-map relative size-full overflow-hidden rounded-lg border border-border bg-surface"
    >
      <div ref={container} className="size-full" />
      {moved ? (
        <button
          type="button"
          onClick={searchHere}
          className="bv-type-label absolute start-1/2 top-3 inline-flex min-h-12 -translate-x-1/2 items-center rounded-full border border-border-strong bg-background px-5 font-semibold text-fg hover:border-primary focus-visible:outline-2 focus-visible:outline-focus rtl:translate-x-1/2"
        >
          {t("map.searchArea")}
        </button>
      ) : null}
      {pins && pins.pins.length === 0 ? (
        <p
          role="status"
          className="bv-type-label absolute start-3 bottom-10 rounded-lg bg-background px-3 py-2 text-fg"
        >
          {t("map.noPlaces")}
        </p>
      ) : pins?.truncated ? (
        <p
          role="status"
          className="bv-type-label absolute start-3 bottom-10 rounded-lg bg-background px-3 py-2 text-fg-muted"
        >
          {t("map.truncated")}
        </p>
      ) : null}
      {chosen ? (
        <div className="absolute inset-x-3 bottom-10 flex items-center gap-3 rounded-lg border border-border-strong bg-background p-3 sm:inset-x-auto sm:end-3 sm:w-80">
          <div className="flex min-w-0 flex-1 flex-col">
            <span className="bv-type-body truncate font-semibold text-fg">{chosen.title}</span>
            <span className="bv-type-label text-fg-muted">{pinLabel(chosen, language)}</span>
          </div>
          <a
            href={`/${language}/b${chosen.path}`}
            className="bv-type-button inline-flex min-h-12 items-center rounded-lg bg-primary px-4 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus"
          >
            {t("map.view")}
          </a>
        </div>
      ) : null}
    </div>
  );
}

/** "from Br 1,800" is too long for a pin: just the amount, or "Free". */
function pinLabel(pin: SearchPin, language: Language): string {
  if (pin.priceFromMinor === undefined || !pin.currency) return "";
  return pin.priceFromMinor === 0
    ? translator(language)("venue.free")
    : price(pin.priceFromMinor, pin.currency, language);
}
