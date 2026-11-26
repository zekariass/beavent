import { Text, useApp } from "@beavent/native";
import { STARS } from "@beavent/venue";

/**
 * Five stars showing a whole-number rating, read aloud as "4 stars". Drawn as text (filled and outlined star
 * characters) so the difference never depends on an icon font having a filled variant.
 */
export function StarRow({ rating }: { rating: number }) {
  const { t } = useApp();
  return (
    <Text
      role="body"
      accessible
      accessibilityRole="image"
      accessibilityLabel={t("reviews.stars", { count: rating })}
    >
      {STARS.map((n) => (n <= rating ? "\u2605" : "\u2606")).join("")}
    </Text>
  );
}
