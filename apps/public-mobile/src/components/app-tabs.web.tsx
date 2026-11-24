import { layout, radius, space } from "@beavent/ui";
import { TabList, TabSlot, TabTrigger, Tabs, type TabTriggerSlotProps } from "expo-router/ui";
import { Pressable, StyleSheet, View } from "react-native";

import { Text } from "@/components/ui";
import { useApp } from "@/lib/app-context";

/** Web build of the app: a token-styled top bar with the same three tabs as the native app. */
export default function AppTabs() {
  const { t, colors } = useApp();
  return (
    <Tabs>
      <TabList asChild>
        <View
          style={{
            borderBottomWidth: StyleSheet.hairlineWidth,
            borderBottomColor: colors.border,
            backgroundColor: colors.background,
          }}
        >
          <View
            style={{
              flexDirection: "row",
              alignItems: "center",
              gap: space[2],
              alignSelf: "center",
              width: "100%",
              maxWidth: layout.maxContentWidth,
              paddingHorizontal: layout.gutter,
              paddingVertical: space[2],
            }}
          >
            <Text role="title2" style={{ marginEnd: "auto" }}>
              {t("app.name")}
            </Text>
            <TabTrigger name="index" href="/" asChild>
              <TabButton>{t("nav.explore")}</TabButton>
            </TabTrigger>
            <TabTrigger name="bookings" href="/bookings" asChild>
              <TabButton>{t("nav.bookings")}</TabButton>
            </TabTrigger>
            <TabTrigger name="account" href="/account" asChild>
              <TabButton>{t("nav.account")}</TabButton>
            </TabTrigger>
          </View>
        </View>
      </TabList>
      <TabSlot style={{ flex: 1 }} />
    </Tabs>
  );
}

function TabButton({ children, isFocused, ref, ...props }: TabTriggerSlotProps) {
  const { colors } = useApp();
  return (
    // Expo SDK 58: TabTriggerSlotProps types `ref` incompatibly with Pressable's ref; keep the ref, fix the type.
    <Pressable
      {...props}
      ref={ref as unknown as React.Ref<React.ComponentRef<typeof Pressable>>}
      accessibilityState={{ selected: !!isFocused }}
      style={({ pressed }) => ({
        minHeight: 36,
        justifyContent: "center",
        paddingHorizontal: space[3],
        borderRadius: radius.full,
        backgroundColor: isFocused
          ? colors.primarySubtle
          : pressed
            ? colors.surfacePressed
            : "transparent",
      })}
    >
      <Text role={isFocused ? "labelStrong" : "label"} tone={isFocused ? "primary" : "muted"}>
        {children}
      </Text>
    </Pressable>
  );
}
