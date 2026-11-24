import { duration, layout, radius, space, type ColorToken } from "@beavent/ui";
import { useEffect, type ReactNode } from "react";
import { View, type DimensionValue } from "react-native";
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
} from "react-native-reanimated";

import { useApp } from "../app-context";

import { Button } from "./button";
import { Icon, type IconName } from "./icon";
import { Text } from "./text";

/** Empty or error state (COMPONENT_GUIDELINES.md §8.2–8.3): why, what to do, one action. No illustration. */
export function EmptyState({
  title,
  body,
  action,
}: {
  title: string;
  body?: string;
  action?: { label: string; onPress: () => void; variant?: "primary" | "secondary" };
}) {
  return (
    <View style={{ gap: space[3], alignItems: "flex-start" }}>
      <Text role="title2">{title}</Text>
      {body && (
        <Text role="body" tone="muted">
          {body}
        </Text>
      )}
      {action && (
        <View style={{ marginTop: space[1] }}>
          <Button
            label={action.label}
            variant={action.variant ?? "secondary"}
            onPress={action.onPress}
          />
        </View>
      )}
    </View>
  );
}

type BannerTone = "info" | "success" | "warning" | "danger";
const BANNER: Record<BannerTone, { fill: ColorToken; icon: ColorToken; name: IconName }> = {
  info: { fill: "infoSubtle", icon: "info", name: "info" },
  success: { fill: "successSubtle", icon: "success", name: "success" },
  warning: { fill: "warningSubtle", icon: "warning", name: "warning" },
  danger: { fill: "dangerSubtle", icon: "danger", name: "error" },
};

/** Inline banner: subtle fill, status-coloured icon, body text in `text` (contrast rule in packages/ui). */
export function Banner({ tone, children }: { tone: BannerTone; children: ReactNode }) {
  const { colors } = useApp();
  const spec = BANNER[tone];
  return (
    <View
      accessibilityRole={tone === "danger" ? "alert" : undefined}
      style={{
        flexDirection: "row",
        gap: space[3],
        alignItems: "flex-start",
        paddingVertical: space[3],
        paddingHorizontal: space[4],
        borderRadius: radius.lg,
        backgroundColor: colors[spec.fill],
      }}
    >
      <Icon name={spec.name} tone={spec.icon} />
      <Text role="label" style={{ flex: 1 }}>
        {children}
      </Text>
    </View>
  );
}

/** Loading placeholder matching the final layout; slow opacity pulse, still under Reduce Motion. */
export function Skeleton({
  width = "100%",
  height = 16,
}: {
  width?: DimensionValue;
  height?: number;
}) {
  const { colors } = useApp();
  const reduced = useReducedMotion();
  const opacity = useSharedValue(1);
  useEffect(() => {
    if (!reduced)
      opacity.value = withRepeat(withTiming(0.5, { duration: duration.slow * 4 }), -1, true);
  }, [reduced, opacity]);
  const animated = useAnimatedStyle(() => ({ opacity: opacity.value }));
  return (
    <Animated.View
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={[
        { width, height, borderRadius: radius.md, backgroundColor: colors.surface },
        animated,
      ]}
    />
  );
}

/** Skeleton for a list of rows (title + metadata line). */
export function SkeletonRows({ count = 3 }: { count?: number }) {
  return (
    <View style={{ gap: layout.sectionGap / 2 }} accessibilityLabel="…">
      {Array.from({ length: count }, (_, i) => (
        <View key={i} style={{ gap: space[2] }}>
          <Skeleton width="60%" height={18} />
          <Skeleton width="35%" height={14} />
        </View>
      ))}
    </View>
  );
}
