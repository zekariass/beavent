import { size as sizes, type ColorToken } from "@beavent/ui";
import { SymbolView } from "expo-symbols";
import type { ComponentProps } from "react";

import { useApp } from "../app-context";

type SymbolName = ComponentProps<typeof SymbolView>["name"];

/** The documented icon set (DESIGN_TOKENS.md §7.2): SF Symbols on iOS, Material Symbols on Android and web. */
const ICONS = {
  info: { ios: "info.circle", android: "info", web: "info" },
  success: { ios: "checkmark.circle", android: "check_circle", web: "check_circle" },
  warning: { ios: "exclamationmark.triangle", android: "warning", web: "warning" },
  error: { ios: "exclamationmark.circle", android: "error", web: "error" },
  chevron: { ios: "chevron.right", android: "chevron_right", web: "chevron_right" },
  check: { ios: "checkmark", android: "check", web: "check" },
  search: { ios: "magnifyingglass", android: "search", web: "search" },
  close: { ios: "xmark", android: "close", web: "close" },
  heart: { ios: "heart", android: "favorite_border", web: "favorite_border" },
  heartFilled: { ios: "heart.fill", android: "favorite", web: "favorite" },
} satisfies Record<string, SymbolName>;

export type IconName = keyof typeof ICONS;

/** Decorative by default (hidden from screen readers); the parent control carries the label. */
export function Icon({
  name,
  size = "md",
  tone = "text",
}: {
  name: IconName;
  size?: keyof typeof sizes.icon;
  tone?: ColorToken;
}) {
  const { colors } = useApp();
  return (
    <SymbolView
      name={ICONS[name]}
      size={sizes.icon[size]}
      tintColor={colors[tone]}
      accessibilityElementsHidden
      importantForAccessibility="no"
    />
  );
}
