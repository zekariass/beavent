import { BeaventTabs, type TabSpec } from "@beavent/native";

/** Consumer tabs: Explore · Saved · Bookings · Account (MARKETPLACE_NAVIGATION.md §2.4). */
const TABS: readonly TabSpec[] = [
  { name: "index", label: "nav.explore", href: "/", sf: "magnifyingglass", md: "search" },
  {
    name: "saved",
    label: "nav.saved",
    href: "/saved",
    sf: { default: "heart", selected: "heart.fill" },
    md: "favorite",
  },
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
