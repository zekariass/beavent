import { BeaventTabs, type TabSpec } from "@beavent/native";

/**
 * Business app tabs (DD13). Only what exists today: Schedule and More. Calendar, Check-in and Sales join when their
 * stages are built, filtered by the member's capabilities and permissions (SERVICES_MARKETPLACE_DESIGN.md §9).
 */
const TABS: readonly TabSpec[] = [
  { name: "index", label: "nav.schedule", href: "/", sf: "calendar", md: "calendar_month" },
  { name: "more", label: "nav.more", href: "/more", sf: "ellipsis.circle", md: "more_horiz" },
];

export default function TabsLayout() {
  return <BeaventTabs tabs={TABS} title="Beavent Business" />;
}
