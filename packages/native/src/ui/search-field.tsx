import { scriptOf } from "@beavent/i18n";
import { borderWidth, radius, size as sizes, space, textStyle } from "@beavent/ui";
import { useState } from "react";
import { Platform, Pressable, TextInput, View } from "react-native";

import { ETHIOPIC_FAMILY } from "../constants/fonts";
import { useApp } from "../app-context";

import { Icon } from "./icon";
import { Text } from "./text";

const FIELD = {
  minHeight: sizes.input,
  borderRadius: radius.lg,
  flexDirection: "row",
  alignItems: "center",
  gap: space[3],
  paddingHorizontal: space[4],
} as const;

/**
 * Search field (COMPONENT_GUIDELINES.md §3.6). Without `onChangeText` it is the **entry**: a button that looks like the
 * field and opens search. With it, the **active** field: type, clear (×), submit from the keyboard.
 */
export function SearchField({
  placeholder,
  value = "",
  onChangeText,
  onSubmit,
  onPress,
  clearLabel,
  autoFocus,
}: {
  placeholder: string;
  value?: string;
  onChangeText?: (text: string) => void;
  onSubmit?: () => void;
  /** Entry form: opens search. */
  onPress?: () => void;
  /** Translated label of the clear button. */
  clearLabel?: string;
  autoFocus?: boolean;
}) {
  const { colors, language } = useApp();
  const [focused, setFocused] = useState(false);
  const script = scriptOf(language);
  const { fontSize } = textStyle("body", script);

  if (!onChangeText) {
    return (
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={placeholder}
        onPress={onPress}
        style={({ pressed }) => ({
          ...FIELD,
          backgroundColor: pressed ? colors.surfacePressed : colors.surface,
        })}
      >
        <Icon name="search" tone="textMuted" />
        <Text tone="muted" lines={1} style={{ flex: 1 }}>
          {placeholder}
        </Text>
      </Pressable>
    );
  }
  return (
    <View
      style={{
        ...FIELD,
        backgroundColor: colors.surface,
        borderWidth: focused ? borderWidth.focus : 0,
        borderColor: colors.primary,
      }}
    >
      <Icon name="search" tone="textMuted" />
      <TextInput
        accessibilityLabel={placeholder}
        value={value}
        onChangeText={onChangeText}
        onSubmitEditing={onSubmit}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        placeholder={placeholder}
        placeholderTextColor={colors.textMuted}
        returnKeyType="search"
        autoFocus={autoFocus}
        autoCorrect={false}
        autoCapitalize="none"
        style={{
          flex: 1,
          minHeight: sizes.input,
          color: colors.text,
          fontSize,
          fontFamily: script === "Ethi" ? ETHIOPIC_FAMILY["400"] : undefined,
          // The field's own border shows focus; the browser's outline would draw a second one.
          ...(Platform.OS === "web" ? ({ outlineStyle: "none" } as object) : {}),
        }}
      />
      {value.length > 0 ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={clearLabel}
          onPress={() => onChangeText("")}
          hitSlop={space[3]}
        >
          <Icon name="close" tone="textMuted" />
        </Pressable>
      ) : null}
    </View>
  );
}
