import type { MessageKey } from "@beavent/i18n";

/** One tab: the route name inside the tabs group, its translated label, web href and platform symbols. */
export interface TabSpec {
  /** File name of the screen in the tabs group (`index`, `bookings` …). */
  name: string;
  label: MessageKey;
  /** URL of the tab on the web build. */
  href: string;
  /** SF Symbol (iOS), optionally with a selected variant. */
  sf: string | { default?: string; selected: string };
  /** Material symbol (Android). */
  md: string;
}

export interface BeaventTabsProps {
  tabs: readonly TabSpec[];
  /** Brand text on the web build's top bar (defaults to the app name). */
  title?: string;
}
