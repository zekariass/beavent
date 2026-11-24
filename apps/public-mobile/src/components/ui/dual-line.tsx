import type { DualText } from "@beavent/calendar";
import { space, type TextRole } from "@beavent/ui";
import { View } from "react-native";

import { Text } from "./text";

/**
 * A date or time in the preferred convention with the other convention muted underneath
 * (DESIGN_TOKENS.md §8.2–8.3), e.g. "ጠዋት 3:00" / "09:00". Screen readers hear both.
 */
export function DualLine({
  value,
  role = "body",
  hideSecondary,
}: {
  value: DualText;
  role?: TextRole;
  hideSecondary?: boolean;
}) {
  return (
    <View
      accessible
      accessibilityLabel={hideSecondary ? value.primary : `${value.primary}, ${value.secondary}`}
      style={{ gap: space[1] }}
    >
      <Text role={role} numeric>
        {value.primary}
      </Text>
      {!hideSecondary && (
        <Text role="caption" tone="muted" numeric>
          {value.secondary}
        </Text>
      )}
    </View>
  );
}
