# Beavent design reference catalog

**Status:** v1, 19 Nov 2026.
**Purpose:** record which products Beavent learns from, which specific patterns, where each lands in Beavent (screen IDs), and what must *not* be copied.

> **About the evidence in this version.** No screenshots were captured or inspected while writing this catalog, and the reference products were not opened live. The pattern descriptions below come from general, publicly known behaviour of these products (as of mid-2026) and from the product analysis already in [doc 02 §1](../../02-product-and-feature-parity.md#1-the-two-reference-platforms). Treat each "What they do" cell as a claim to re-verify; products change often. §7 describes how to add annotated screenshots so this catalog becomes evidence-based.

---

## 1. Fresha — services marketplace

**Links:** [fresha.com](https://www.fresha.com) (consumer marketplace) · [fresha.com/for-business](https://www.fresha.com/for-business) · [fresha.com/pricing](https://www.fresha.com/pricing) · [Up Next 2026 roadmap](https://www.fresha.com/blog/Up-Next-2026) · Fresha consumer app and Fresha for Business app (App Store, Google Play).
**Why it's the benchmark:** the most complete consumer booking flow for beauty and wellness, with the business tooling (menus, variants, team pricing, processing time) that makes that flow accurate.

| Pattern | What they do | Beavent adaptation | Where |
|---|---|---|---|
| Search by need, place and time | Treatment/venue + location + date/time search entry | Mobile: one search field + chips; web: "What · Where · When" segmented search | C-1, C-2, S-02 |
| Services in search results | Results show a venue with a few relevant services, durations and prices | `VenueResultRow` with up to 3 matching service lines — with a 104 dp thumbnail instead of a photo carousel (data budget) | S-02 |
| Venue page structure | Gallery, rating, address, opening times; services grouped by category with sticky category navigation; team; reviews; about | Same information architecture, plus landmark-first address, dual-time opening hours and plain-sentence policies | S-04 |
| Basket-first booking | Choose one or more services, then professional, then time, then confirm | Same order (DD4), skipping steps with one answer | S-06 … S-10 |
| "Any professional" | Default choice with maximum availability; specific professionals with their own prices | Same, with price deltas from `ResolvedPrice` | S-07 |
| Date strip + time list | Horizontal days, then times for the chosen day | DateStrip in the user's calendar + TimeSlotGrid grouped by Ethiopian day periods with dual time | S-08 |
| Policy before confirmation | Cancellation/no-show terms shown at checkout | Turned into exact deadlines and amounts for this booking | S-10 |
| Appointment management | Upcoming/past, reschedule, cancel, directions, rebook | Unified with tickets in one Bookings tab | C-4, S-11 … S-14 |
| Verified reviews | Reviews only from completed visits | Same rule (`discovery.reviews` requires an appointment or order) | S-16 |
| Business menu building | Service categories, pricing options (variants), processing/finishing time, team-specific prices | P-01 … P-03 with StepsEditor and PriceMatrix explaining "most specific wins" | P-01 … P-03 |

**Do not copy:** Fresha's violet/black brand, pill-shaped black buttons, typography, illustrations, or iconography; photo carousels inside result lists (too heavy for the launch market's data budgets); card-on-file no-show protection (barely viable in Ethiopia — Beavent uses deposits via mobile money, doc 06 §2.6); marketplace upsell surfaces.

## 2. Eventbrite — events marketplace

**Links:** [eventbrite.com](https://www.eventbrite.com) · [Organizer features](https://www.eventbrite.com/organizer/features/all-features/) · [Product roadmap 2026](https://www.eventbrite.com/product-updates/roadmap-2026) · Eventbrite attendee app and Eventbrite Organizer app (App Store, Google Play).
**Why it's the benchmark:** the standard for self-service ticketing: discovery by date and category, a scannable event page, a fast ticket checkout, and attendee/organizer tools including check-in.

| Pattern | What they do | Beavent adaptation | Where |
|---|---|---|---|
| Date-first discovery | Quick filters such as Today / This weekend, plus categories and city | Date chips + category chips; "This weekend" defined as Friday evening → Sunday | E-01, E-03 |
| Event card anatomy | Image, date line, title, venue, price | Same order; date line in `primary`, dual time, price all-in; one status label max | E-01, E-03 (EventCard, EventResultRow) |
| Event page | Cover, date/time and location blocks, description, organizer with follow, refund policy, sticky ticket button with price | Same skeleton; content blocks from `events.content` in a fixed order; sticky bar states table | E-05 |
| Ticket selection | Ticket types with quantity steppers; promo/access code entry | Same; all-in prices; sequential release and price phases explained in captions | E-07 |
| Reservation timer | Countdown once tickets are held | HoldTimer: a plain "held until" time, countdown only in the last 2 minutes | E-07 … E-09 |
| Add-ons as a separate step | Merchandise/add-ons in their own checkout step (2026 roadmap: shipped) | E-08b, only when the event has add-ons | E-08b |
| Order and attendee questions | Organizer-defined checkout forms | One forms engine shared with consultation forms | E-08, S-09 |
| Mobile tickets | Tickets with QR in the app | TicketPass that always works offline, with a short SMS serial under the QR | E-11 |
| Follow organizers, auto-follow after purchase | Follow graph drives notifications | Same (`discovery.follows.source = AFTER_PURCHASE`), with a visible Undo on confirmation | E-10, E-15 |
| Date changes and cancellations | Automatic attendee emails on date/location change (2026 roadmap: shipped) | E-16 banners + notifications through all channels (SMS/Telegram too) | E-16 |
| Organizer check-in app | Scanning, search, counters | B-3 with offline manifest and result panels readable at arm's length | B-3 |

**Do not copy:** the orange brand and type; dense promotional discovery feeds; fee presentation that adds charges late in checkout (Beavent shows all-in prices from the first view, DD6); ad slots without strict limits (E-04 caps them).

## 3. Airbnb — discovery, search, imagery

**Link:** [airbnb.com](https://www.airbnb.com) · Airbnb app.
| Pattern | Beavent adaptation | Where |
|---|---|---|
| Segmented search entry (Where / When / Who) on web | "What · Where · When" on the web marketplace home | C-1 (web), §8 of NAV |
| Horizontal category scroller under search | Text-only category chips (no icons, P7) | C-1, S-01, E-01 |
| Floating "Map" pill to switch list/map | Same pill, once a map dependency is approved (Q3) | S-03, E-03 |
| Wishlists | Saved tab with places, events and professionals | C-3 |
| Photo-first listing detail with a sticky price + action footer | Profile/detail template with StickyActionBar | S-04, E-05 |
| Trips tab as one itinerary | Bookings tab interleaving appointments and tickets | C-4 |
| Showing total prices | All-in price display principle | DD6 |

**Do not copy:** Airbnb's brand colour, typeface, 3D icons, or per-card photo carousels in lists.

## 4. Vinted — listings and selling workflows

**Link:** [vinted.com](https://www.vinted.com) · Vinted app.
| Pattern | Beavent adaptation | Where |
|---|---|---|
| Photo-first listing creation: upload photos first, then details, with a preview of the result | Partner gallery upload with processing states; event wizard cover crop with live card/page preview; listing preview + checklist | P-07, P-08, P-21 |
| Item cards where price is prominent and metadata is one short line | `PriceText` prominence and MetaLine discipline in rows | S-02, E-03 |
| A named, explained buyer fee (buyer protection) | Service-fee info sheet that says what the fee is and that it's already in the prices | E-09, S-10 |
| Simple seller-side status for listings | Listing status (Hidden / In review / Listed) with what to fix | P-08 |

**Do not copy:** the dense 2-up product grid for services (services aren't products), negotiation/offer mechanics, its colour and type.

## 5. Revolut — polish, numbers, states

**Link:** [revolut.com](https://www.revolut.com) · Revolut app.
| Pattern | Beavent adaptation | Where |
|---|---|---|
| Typographic polish: few sizes, strong weight contrast, numbers as the hero of money screens, tabular figures | Type roles (DESIGN_TOKENS §3), `PriceText large`, tabular numerals everywhere money/time appears | Tokens, C-7, E-12 |
| Clear transaction states (pending → completed → reverted) | Refund and payment status rows; "Pending payment" labels | S-11, E-12, E-14 |
| Calm waiting states for asynchronous money | PaymentWaiting: what to do, what's held, safe to leave | C-7 |
| Quick re-authentication for sensitive actions | Step-up code sheet | C-6 |
| High-quality dark mode | Dark theme by surface elevation, no pure black | Tokens §2.3 |

**Do not copy:** gradients, glassy/blurred cards, a feature-dashboard home screen, aggressive cross-sell.

## 6. Platform and standards references

| Reference | Use |
|---|---|
| [Apple Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/) | Tab bars, sheets, navigation, sign in with Apple placement |
| [Material Design 3](https://m3.material.io/) | Android navigation, back behaviour, touch targets |
| [WCAG 2.2](https://www.w3.org/TR/WCAG22/) | Contrast (1.4.3, 1.4.11), target size (2.5.8), focus, status messages |
| [Expo SDK 58 docs](https://docs.expo.dev/versions/v58.0.0/) | Native tabs, `expo-image`, `expo-symbols`, `expo-web-browser` — always check the versioned docs (`mobile/AGENTS.md`) |
| [Chapa developer docs](https://developer.chapa.co/) | Hosted checkout and direct charge behaviour that PaymentWaiting must explain (doc 07 §1.1) |

**Local references to capture (high value, not yet collected):** what a telebirr / CBE Birr / M-PESA push prompt looks like on the customer's phone (so PaymentWaiting's steps match reality); Chapa's hosted checkout pages; how Ethiopian event organizers currently announce events on Telegram channels (link previews make the event page's Open Graph data part of the design); Amharic date/time conventions on printed tickets and appointment cards in Addis Ababa salons.

## 7. How to add evidence (screenshots)

1. **Where:** `docs/design/beavent/references/<product>/<YYYY-MM-DD>-<screen>.png` (e.g. `references/fresha/2026-12-02-venue-page.png`). Mobile captures at 360 dp-equivalent width where possible.
2. **Annotation file** next to each image (`…-venue-page.md`):
   ```markdown
   **Source:** Fresha iOS app 5.x, captured 2026-12-02 on iPhone 15 (en, light)
   **Screen:** venue page, services section
   **Learn:** sticky category tabs scroll-spy; service rows show duration under the name
   **Don't copy:** pill buttons, brand colour, carousel in results
   **Beavent screens:** S-04, S-06
   **Annotations:** 1) category tabs stick under the header … 2) …
   ```
3. **Rules:** internal design reference only — never ship, publish or reuse these images or their assets; capture only public screens (no other people's personal data; blur names in reviews); record the date because products change; update the "What they do" cells above when a capture contradicts them.
4. **Keep it small:** 5–10 annotated captures per benchmark covering the screens listed in the tables are enough.
