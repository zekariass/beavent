import { scriptOf } from "@beavent/i18n";
import { borderWidth, radius, size as sizes, space, textStyle } from "@beavent/ui";
import { useState } from "react";
import { TextInput, View, type TextInputProps } from "react-native";

import { ETHIOPIC_FAMILY } from "../constants/fonts";
import { useApp } from "../app-context";

import { Icon } from "./icon";
import { Text } from "./text";

export type TextFieldProps = Omit<TextInputProps, "style" | "placeholderTextColor"> & {
  /** Always-visible label above the field (no floating labels). */
  label: string;
  helper?: string;
  error?: string | null;
};

/** Text input (COMPONENT_GUIDELINES.md §3.3): label above, 52 dp field, 2 dp focus/error outline, message below. */
export function TextField({ label, helper, error, onFocus, onBlur, ...rest }: TextFieldProps) {
  const { colors, language } = useApp();
  const [focused, setFocused] = useState(false);
  const script = scriptOf(language);
  const { fontSize } = textStyle("body", script);
  return (
    <View style={{ gap: space[2], alignSelf: "stretch" }}>
      <Text role="label">{label}</Text>
      <TextInput
        accessibilityLabel={label}
        {...rest}
        onFocus={(event) => {
          setFocused(true);
          onFocus?.(event);
        }}
        onBlur={(event) => {
          setFocused(false);
          onBlur?.(event);
        }}
        placeholderTextColor={colors.textMuted}
        style={{
          minHeight: sizes.input,
          paddingHorizontal: space[4],
          borderRadius: radius.lg,
          borderWidth: error || focused ? borderWidth.focus : borderWidth.input,
          borderColor: error ? colors.danger : focused ? colors.primary : colors.borderStrong,
          backgroundColor: rest.editable === false ? colors.surface : colors.background,
          color: colors.text,
          fontSize,
          fontFamily: script === "Ethi" ? ETHIOPIC_FAMILY["400"] : undefined,
        }}
      />
      {error ? (
        <View
          style={{ flexDirection: "row", gap: space[1], alignItems: "center" }}
          accessibilityLiveRegion="polite"
        >
          <Icon name="error" size="sm" tone="danger" />
          <Text role="caption" tone="danger" style={{ flexShrink: 1 }}>
            {error}
          </Text>
        </View>
      ) : helper ? (
        <Text role="caption" tone="muted">
          {helper}
        </Text>
      ) : null}
    </View>
  );
}
