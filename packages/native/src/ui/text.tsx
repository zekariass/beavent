import { scriptOf } from "@beavent/i18n";
import { scriptOfText, textStyle, type ColorToken, type TextRole } from "@beavent/ui";
import { Children, type ReactNode } from "react";
import {
  Text as NativeText,
  type StyleProp,
  type TextProps as NativeTextProps,
  type TextStyle,
} from "react-native";

import { ETHIOPIC_FAMILY } from "../constants/fonts";
import { useApp } from "../app-context";

export type TextTone =
  | "default"
  | "muted"
  | "subtle"
  | "primary"
  | "success"
  | "warning"
  | "danger"
  | "inverse"
  | "onPrimary"
  | "onDanger";

const TONE: Record<TextTone, ColorToken> = {
  default: "text",
  muted: "textMuted",
  subtle: "textSubtle",
  primary: "primary",
  success: "success",
  warning: "warning",
  danger: "danger",
  inverse: "textInverse",
  onPrimary: "onPrimary",
  onDanger: "onDanger",
};

/** Layout-only styles: size, weight, line height, colour and letter case come from the role and tone. */
type LayoutTextStyle = Omit<
  TextStyle,
  | "fontSize"
  | "fontWeight"
  | "lineHeight"
  | "color"
  | "fontFamily"
  | "letterSpacing"
  | "textTransform"
>;

export type TextProps = Omit<NativeTextProps, "style" | "role"> & {
  role?: TextRole;
  tone?: TextTone;
  /** Tabular figures for prices, times and counts. */
  numeric?: boolean;
  /** Maximum lines before an ellipsis. */
  lines?: number;
  style?: StyleProp<LayoutTextStyle>;
  children?: ReactNode;
};

const plainText = (children: ReactNode): string =>
  Children.toArray(children)
    .filter((child) => typeof child === "string" || typeof child === "number")
    .join("");

/**
 * The only way to render text (docs/design/beavent/COMPONENT_GUIDELINES.md §2.1). Ge'ez text — in the UI language or
 * inside the string — gets Ethiopic metrics: line height 1.7 for body text and never smaller than 14 px.
 */
export function Text({
  role = "body",
  tone = "default",
  numeric,
  lines,
  style,
  children,
  ...rest
}: TextProps) {
  const { language, colors } = useApp();
  const script =
    scriptOf(language) === "Ethi" || scriptOfText(plainText(children)) === "Ethi" ? "Ethi" : "Latn";
  const metrics = textStyle(role, script);
  return (
    <NativeText
      {...rest}
      numberOfLines={lines}
      style={[
        script === "Ethi"
          ? // Bundled Noto Sans Ethiopic: the weight is in the family name.
            { ...metrics, fontWeight: undefined, fontFamily: ETHIOPIC_FAMILY[metrics.fontWeight] }
          : metrics,
        { color: colors[TONE[tone]] },
        numeric && { fontVariant: ["tabular-nums"] },
        style,
      ]}
    >
      {children}
    </NativeText>
  );
}
