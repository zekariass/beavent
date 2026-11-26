import { EmptyState, useApp } from "@beavent/native";

/** The browser build of the app has no native map; the list is the way to the same places. */
export function PlacesMap() {
  const { t } = useApp();
  return <EmptyState title={t("map.unavailable")} />;
}
