import { borderWidth, radius, size as sizes } from "@beavent/ui";
import { Pressable } from "react-native";

import { useApp } from "../app-context";
import { selectionFeedback } from "../haptics";

import { Text } from "./text";

/** Choice chip (COMPONENT_GUIDELINES.md §3.10): 36 dp pill; selected = primarySubtle fill + 1.5 dp primary border. */
export function Chip({
  label,
  selected,
  onPress,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
}) {
  const { colors } = useApp();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected }}
      onPress={() => {
        if (!selected) selectionFeedback();
        onPress();
      }}
      hitSlop={(sizes.touchTarget - sizes.chip) / 2}
      style={({ pressed }) => ({
        minHeight: sizes.chip,
        paddingHorizontal: 14,
        borderRadius: radius.full,
        justifyContent: "center",
        borderWidth: borderWidth.selected,
        borderColor: selected ? colors.primary : "transparent",
        backgroundColor: selected
          ? colors.primarySubtle
          : pressed
            ? colors.surfacePressed
            : colors.surface,
      })}
    >
      <Text role={selected ? "labelStrong" : "label"} tone={selected ? "primary" : "default"}>
        {label}
      </Text>
    </Pressable>
  );
}
