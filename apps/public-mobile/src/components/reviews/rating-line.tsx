import { Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { ratingLabel, ratingLine } from "@beavent/venue";
import { View } from "react-native";

/** "★ 4.5 · 12 reviews" on a result row or card; nothing when nobody has reviewed the place. */
export function RatingLine({ average, count }: { average?: number; count: number }) {
  const { t, language } = useApp();
  const line = ratingLine(average, count, language, t);
  if (!line || average === undefined) return null;
  return (
    <View
      accessible
      accessibilityLabel={`${ratingLabel(average, language, t)}, ${t("reviews.count", { count })}`}
      style={{ flexDirection: "row", alignItems: "center", gap: space[1] }}
    >
      <Text role="label">{"\u2605 " + line}</Text>
    </View>
  );
}
