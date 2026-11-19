// Source catalogue: every key exists here. Other languages may lag; missing keys fall back to English.
// Messages use {name} placeholders and {count, plural, one {…} other {…}} (ICU subset, see ../index.ts).
export const en = {
  "app.name": "Beavent",
  "app.tagline": "Book beauty and wellness. Find events. All in one place.",
  "nav.explore": "Explore",
  "nav.bookings": "Bookings",
  "nav.tickets": "Tickets",
  "nav.account": "Account",
  "nav.signIn": "Sign in",
  "home.services": "Book a salon, spa or barber",
  "home.events": "Find events near you",
  "home.today": "Today",
  "home.now": "Now",
  "home.calendarNote": "Dates in the Ethiopian calendar; times in Ethiopian time.",
  "language.label": "Language",
  "calendar.ethiopian": "Ethiopian calendar",
  "calendar.gregorian": "Gregorian calendar",
  "time.ethiopian": "Ethiopian time",
  "time.standard": "International time",
  "business.title": "Beavent for business",
  "business.subtitle":
    "Calendar, sales, team and events for salons, spas, professionals and organizers.",
  "business.start": "Create your business",
  "business.signIn": "Sign in to your workspace",
  "bookings.count": "{count, plural, =0 {No bookings} one {# booking} other {# bookings}}",
  "greeting.named": "Hello, {name}!",
  "error.generic": "Something went wrong. Please try again.",
  "error.offline": "You are offline. We will retry when you are back online.",
} as const;

export type MessageKey = keyof typeof en;
export type Catalogue = Partial<Record<MessageKey, string>>;
