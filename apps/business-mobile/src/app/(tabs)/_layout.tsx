import { BeaventTabs, type TabSpec } from "@beavent/native";

/**
 * Business app tabs (DD13, MARKETPLACE_NAVIGATION.md §6): Today (B-1), Calendar (B-2), More (B-5; "My schedule",
 * preferences). Check-in and Sales join with their stages, filtered by the member's capabilities and permissions.
 */
const TABS: readonly TabSpec[] = [
  { name: "index", label: "nav.today", href: "/", sf: "clock", md: "schedule" },
  {
    name: "calendar",
    label: "nav.calendar",
    href: "/calendar",
    sf: "calendar",
    md: "calendar_month",
  },
  { name: "more", label: "nav.more", href: "/more", sf: "ellipsis.circle", md: "more_horiz" },
];

export default function TabsLayout() {
  return <BeaventTabs tabs={TABS} title="Beavent Business" />;
}
