import { layout, space } from "@beavent/ui";
import type { ReactNode } from "react";
import { ScrollView, View } from "react-native";
import { SafeAreaView, type Edge } from "react-native-safe-area-context";

import { TAB_BAR_INSET } from "../constants/layout";
import { useApp } from "../app-context";

import { Button } from "./button";
import { Text } from "./text";

/**
 * A screen: background, safe areas, 16 dp gutters, centred at 800 dp on wide screens, one vertical scroll, 32 dp
 * between sections. `inTabs` pads for the native tab bar; `edges` is empty under a native stack header.
 */
export function Screen({
  children,
  inTabs,
  edges = ["top"],
}: {
  children: ReactNode;
  inTabs?: boolean;
  edges?: Edge[];
}) {
  const { colors } = useApp();
  return (
    <SafeAreaView edges={edges} style={{ flex: 1, backgroundColor: colors.background }}>
      <ScrollView
        contentInsetAdjustmentBehavior="automatic"
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{
          alignSelf: "center",
          width: "100%",
          maxWidth: layout.maxContentWidth,
          paddingHorizontal: layout.gutter,
          paddingTop: space[4],
          paddingBottom: layout.sectionGap + (inTabs ? TAB_BAR_INSET : 0),
          gap: layout.sectionGap,
        }}
      >
        {children}
      </ScrollView>
    </SafeAreaView>
  );
}

/** Screen title for tab roots (title1, left-aligned). */
export function ScreenTitle({ children }: { children: string }) {
  return (
    <Text role="title1" accessibilityRole="header">
      {children}
    </Text>
  );
}

/** A section: title2 heading, 12 dp, content. No background or border (DD9). */
export function Section({
  title,
  action,
  children,
}: {
  title?: string;
  action?: { label: string; onPress: () => void };
  children: ReactNode;
}) {
  return (
    <View style={{ gap: layout.titleGap }}>
      {(title || action) && (
        <View style={{ flexDirection: "row", alignItems: "center", gap: space[2] }}>
          {title && (
            <Text role="title2" accessibilityRole="header" style={{ flex: 1 }}>
              {title}
            </Text>
          )}
          {action && (
            <Button variant="tertiary" size="sm" label={action.label} onPress={action.onPress} />
          )}
        </View>
      )}
      {children}
    </View>
  );
}
