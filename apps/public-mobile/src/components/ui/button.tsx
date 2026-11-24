import { radius, size as sizes, space, type ColorToken } from "@beavent/ui";
import { ActivityIndicator, Pressable, View, type PressableProps } from "react-native";

import { useApp } from "@/lib/app-context";

import { Text, type TextTone } from "./text";

export type ButtonVariant =
  "primary" | "secondary" | "tertiary" | "destructive" | "destructiveTertiary";
export type ButtonSize = "lg" | "md" | "sm";

const FILL: Record<
  ButtonVariant,
  { rest?: ColorToken; pressed?: ColorToken; label: TextTone; spinner: ColorToken }
> = {
  primary: { rest: "primary", pressed: "primaryPressed", label: "onPrimary", spinner: "onPrimary" },
  secondary: { rest: "surface", pressed: "surfacePressed", label: "default", spinner: "text" },
  tertiary: { pressed: "surfacePressed", label: "primary", spinner: "primary" },
  destructive: { rest: "danger", pressed: "danger", label: "onDanger", spinner: "onDanger" },
  destructiveTertiary: { pressed: "surfacePressed", label: "danger", spinner: "danger" },
};

const PADDING: Record<ButtonSize, number> = { lg: space[5], md: space[4], sm: space[3] };

export type ButtonProps = Omit<PressableProps, "children" | "style"> & {
  label: string;
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Full width (sticky action bars, sheets, forms). */
  block?: boolean;
  loading?: boolean;
};

/**
 * Buttons (COMPONENT_GUIDELINES.md §3.1): one `primary` per screen; label is a verb phrase. Loading keeps the width
 * and disables the button; disabled uses the surface fill with subtle text.
 */
export function Button({
  label,
  variant = "primary",
  size = "md",
  block,
  loading,
  disabled,
  ...rest
}: ButtonProps) {
  const { colors } = useApp();
  const spec = FILL[variant];
  const inactive = disabled || loading;
  const height = sizes.button[size];
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: !!inactive, busy: !!loading }}
      disabled={inactive}
      hitSlop={height < sizes.touchTarget ? (sizes.touchTarget - height) / 2 : undefined}
      {...rest}
      style={({ pressed }) => ({
        minHeight: height,
        paddingHorizontal: PADDING[size],
        borderRadius: radius.lg,
        alignItems: "center",
        justifyContent: "center",
        alignSelf: block ? "stretch" : "flex-start",
        backgroundColor:
          disabled && spec.rest
            ? colors.surface
            : pressed && spec.pressed
              ? colors[spec.pressed]
              : spec.rest
                ? colors[spec.rest]
                : "transparent",
        opacity: variant === "destructive" && pressed ? 0.85 : 1,
      })}
    >
      <View style={{ opacity: loading ? 0 : 1 }}>
        <Text
          role={size === "sm" ? "labelStrong" : "buttonLarge"}
          tone={disabled ? "subtle" : spec.label}
          style={{ textAlign: "center" }}
        >
          {label}
        </Text>
      </View>
      {loading && (
        <ActivityIndicator
          color={colors[spec.spinner]}
          style={{ position: "absolute" }}
          accessibilityElementsHidden
        />
      )}
    </Pressable>
  );
}
