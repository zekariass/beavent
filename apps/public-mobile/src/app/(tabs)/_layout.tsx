import { BeaventTabs, type TabSpec } from "@beavent/native";

/** Consumer tabs at this stage: Explore · Bookings · Account (MARKETPLACE_NAVIGATION.md §2.4; Saved arrives in Stage 8). */
const TABS: readonly TabSpec[] = [
  { name: "index", label: "nav.explore", href: "/", sf: "magnifyingglass", md: "search" },
  {
    name: "bookings",
    label: "nav.bookings",
    href: "/bookings",
    sf: "calendar",
    md: "calendar_month",
  },
  {
    name: "account",
    label: "nav.account",
    href: "/account",
    sf: { default: "person.crop.circle", selected: "person.crop.circle.fill" },
    md: "account_circle",
  },
];

export default function TabsLayout() {
  return <BeaventTabs tabs={TABS} />;
}
