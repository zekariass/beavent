import { layout, radius, space } from "@beavent/ui";
import type { ReactNode } from "react";
import { Modal, Pressable, ScrollView, View, useWindowDimensions } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { useApp } from "../app-context";

import { Button } from "./button";
import { Text } from "./text";

/**
 * Bottom sheet (COMPONENT_GUIDELINES.md §7.1): `surfaceRaised`, top radius `xl`, a handle, the scrim behind. Height
 * follows the content up to 90 % of the window, then the content scrolls; a footer stays visible below it. Closes from
 * the scrim, the Close button and the Android back button.
 */
export function BottomSheet({
  visible,
  onClose,
  title,
  closeLabel,
  footer,
  children,
}: {
  visible: boolean;
  onClose: () => void;
  title: string;
  /** Translated label of the Close button. */
  closeLabel: string;
  footer?: ReactNode;
  children: ReactNode;
}) {
  const { colors } = useApp();
  const { height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <View style={{ flex: 1, justifyContent: "flex-end" }}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={closeLabel}
          onPress={onClose}
          style={{
            position: "absolute",
            top: 0,
            bottom: 0,
            start: 0,
            end: 0,
            backgroundColor: colors.scrim,
          }}
        />
        <View
          accessibilityViewIsModal
          style={{
            alignSelf: "center",
            width: "100%",
            maxWidth: layout.maxContentWidth,
            maxHeight: height * 0.9,
            backgroundColor: colors.surfaceRaised,
            borderTopStartRadius: radius.xl,
            borderTopEndRadius: radius.xl,
            paddingBottom: Math.max(insets.bottom, space[4]),
          }}
        >
          <View
            style={{
              alignSelf: "center",
              width: 36,
              height: 4,
              borderRadius: radius.full,
              backgroundColor: colors.border,
              marginTop: space[2],
            }}
          />
          <View
            style={{
              flexDirection: "row",
              alignItems: "center",
              gap: space[2],
              paddingStart: layout.gutter,
              paddingEnd: space[2],
              paddingTop: space[2],
            }}
          >
            <Text role="title2" accessibilityRole="header" style={{ flex: 1 }}>
              {title}
            </Text>
            <Button variant="tertiary" size="sm" label={closeLabel} onPress={onClose} />
          </View>
          <ScrollView
            keyboardShouldPersistTaps="handled"
            contentContainerStyle={{
              paddingHorizontal: layout.gutter,
              paddingVertical: space[3],
              gap: space[4],
            }}
          >
            {children}
          </ScrollView>
          {footer ? (
            <View style={{ paddingHorizontal: layout.gutter, paddingTop: space[2], gap: space[2] }}>
              {footer}
            </View>
          ) : null}
        </View>
      </View>
    </Modal>
  );
}
