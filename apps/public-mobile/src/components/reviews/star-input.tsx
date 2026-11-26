import { selectionFeedback, Text, useApp } from "@beavent/native";
import { size } from "@beavent/ui";
import { STARS } from "@beavent/venue";
import { Pressable, View } from "react-native";

/** Choose 1 to 5 stars: five 48 dp targets, each announced by its count and whether it is the chosen one. */
export function StarInput({
  value,
  onChange,
}: {
  value: number;
  onChange: (rating: number) => void;
}) {
  const { t } = useApp();
  return (
    <View accessibilityRole="radiogroup" style={{ flexDirection: "row" }}>
      {STARS.map((n) => (
        <Pressable
          key={n}
          accessibilityRole="radio"
          accessibilityLabel={t("reviews.stars", { count: n })}
          accessibilityState={{ checked: value === n }}
          onPress={() => {
            selectionFeedback();
            onChange(n);
          }}
          style={{
            width: size.touchTarget,
            height: size.touchTarget,
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          <Text role="title1">{n <= value ? "\u2605" : "\u2606"}</Text>
        </Pressable>
      ))}
    </View>
  );
}
