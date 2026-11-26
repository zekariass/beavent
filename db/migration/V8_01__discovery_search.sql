-- =============================================================================
-- 8.1: discovery and search
--      what the listings read model needs beyond V1: a normalised search text for fuzzy (trigram) matching,
--      the date a place or event first appeared (the "New on Beavent" shelf), and the "open now" check.
-- =============================================================================

-- The words of a listing after the projector's normalisation (lower case, no accents, Ge'ez spelling variants folded
-- to one form, plus Latin transliterations of Ge'ez words and the reverse), used for partial and misspelt matches.
-- `search_document` holds the same words as a weighted tsvector for exact and prefix matches (doc 06 §5.5).
ALTER TABLE discovery.listings
  ADD COLUMN search_text  text        NOT NULL DEFAULT '',
  ADD COLUMN listed_since timestamptz NOT NULL DEFAULT now();

CREATE INDEX listings_search_text_trgm ON discovery.listings USING gin (search_text gin_trgm_ops) WHERE is_listed;
CREATE INDEX listings_market_kind_idx  ON discovery.listings (market_id, kind) WHERE is_listed;
CREATE INDEX listings_listed_since_idx ON discovery.listings (listed_since DESC) WHERE is_listed;

COMMENT ON COLUMN discovery.listings.attributes IS
  'Public facts for filters and result rows: businessSlug, locationSlug or eventSlug, timezone, hours [{d,o,c}], '
  'slots {local date: [MORNING|AFTERNOON|EVENING]} for the next weeks (bookable times, or occurrences for events), '
  'deliveryModes, audiences, languages, serviceMode, services (a short menu), category, area, landmark, lat, lng.';

-- Is a place open at an instant? hours: [{"d": ISO weekday 1-7, "o": "09:00", "c": "18:00"}] in the place's zone.
-- Announced closures and public holidays are not considered here: the venue page and the availability engine apply them.
CREATE FUNCTION discovery.is_open_at(hours jsonb, tz text, at timestamptz) RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT EXISTS (
    SELECT 1
      FROM jsonb_to_recordset(CASE WHEN jsonb_typeof(hours) = 'array' THEN hours ELSE '[]'::jsonb END) AS h(d int, o time, c time)
     WHERE h.d = extract(isodow FROM (at AT TIME ZONE tz))::int
       AND (at AT TIME ZONE tz)::time >= h.o
       AND (at AT TIME ZONE tz)::time <  h.c)
$$;

-- One listing per place and per event (the projector upserts on these).
CREATE UNIQUE INDEX listings_location_uq ON discovery.listings (location_id) WHERE location_id IS NOT NULL;
CREATE UNIQUE INDEX listings_event_uq    ON discovery.listings (event_id)    WHERE event_id IS NOT NULL;
