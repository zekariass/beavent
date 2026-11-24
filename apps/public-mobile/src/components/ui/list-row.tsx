import { borderWidth, layout, radius, size as sizes, space } from "@beavent/ui";
import { Children, Fragment, isValidElement, type ReactNode } from "react";
import { Pressable, StyleSheet, View } from "react-native";

import { useApp } from "@/lib/app-context";
import { selectionFeedback } from "@/lib/haptics";

import { Icon } from "./icon";
import { Text } from "./text";

/** Hairline divider inset to where the row text starts. */
export function Divider({ inset = space[4] }: { inset?: number }) {
  const { colors } = useApp();
  return (
    <View
      style={{
        height: StyleSheet.hairlineWidth,
        backgroundColor: colors.border,
        marginStart: inset,
      }}
    />
  );
}

/**
 * Inset grouped list on `surface` — only for settings and account screens (SCREEN_DESIGN_STANDARDS.md §2.10).
 * Rows are separated by hairline dividers.
 */
export function Group({ children }: { children: ReactNode }) {
  const { colors } = useApp();
  const rows = Children.toArray(children).filter(isValidElement);
  return (
    <View style={{ backgroundColor: colors.surface, borderRadius: radius.lg, overflow: "hidden" }}>
      {rows.map((row, index) => (
        <Fragment key={row.key ?? index}>
          {index > 0 && <Divider />}
          {row}
        </Fragment>
      ))}
    </View>
  );
}

export type ListRowProps = {
  title: string;
  subtitle?: string;
  /** Text or element shown at the trailing edge. */
  trailing?: ReactNode;
  /** Navigation rows show a chevron. */
  onPress?: () => void;
  chevron?: boolean;
  tone?: "default" | "danger";
  accessibilityLabel?: string;
};

/** A list row (COMPONENT_GUIDELINES.md §4.3): min 56 dp, title + optional subtitle, one trailing element. */
export function ListRow({
  title,
  subtitle,
  trailing,
  onPress,
  chevron,
  tone = "default",
  accessibilityLabel,
}: ListRowProps) {
  const { colors } = useApp();
  const content = (
    <>
      <View style={{ flex: 1, gap: space[1] }}>
        <Text role="body" tone={tone === "danger" ? "danger" : "default"}>
          {title}
        </Text>
        {subtitle && (
          <Text role="label" tone="muted">
            {subtitle}
          </Text>
        )}
      </View>
      {typeof trailing === "string" ? (
        <Text role="label" tone="muted">
          {trailing}
        </Text>
      ) : (
        trailing
      )}
      {chevron && <Icon name="chevron" size="sm" tone="textMuted" />}
    </>
  );
  const style = {
    minHeight: layout.rowMinHeight,
    paddingVertical: layout.rowPaddingY,
    paddingHorizontal: space[4],
    flexDirection: "row" as const,
    alignItems: "center" as const,
    gap: space[3],
  };
  return onPress ? (
    <Pressable
      accessibilityRole={chevron ? "link" : "button"}
      accessibilityLabel={accessibilityLabel}
      onPress={onPress}
      style={({ pressed }) => [style, pressed && { backgroundColor: colors.surfacePressed }]}
    >
      {content}
    </Pressable>
  ) : (
    <View style={style} accessible accessibilityLabel={accessibilityLabel}>
      {content}
    </View>
  );
}

/** A single-choice option row with a radio on the trailing edge. */
export function OptionRow({
  title,
  subtitle,
  selected,
  onPress,
}: {
  title: string;
  subtitle?: string;
  selected: boolean;
  onPress: () => void;
}) {
  const { colors } = useApp();
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ selected, checked: selected }}
      accessibilityLabel={subtitle ? `${title}, ${subtitle}` : title}
      onPress={() => {
        if (!selected) selectionFeedback();
        onPress();
      }}
      style={({ pressed }) => ({
        minHeight: layout.rowMinHeight,
        paddingVertical: layout.rowPaddingY,
        paddingHorizontal: space[4],
        flexDirection: "row",
        alignItems: "center",
        gap: space[3],
        backgroundColor: pressed ? colors.surfacePressed : "transparent",
      })}
    >
      <View style={{ flex: 1, gap: space[1] }}>
        <Text role={selected ? "bodyStrong" : "body"}>{title}</Text>
        {subtitle && (
          <Text role="label" tone="muted">
            {subtitle}
          </Text>
        )}
      </View>
      <View
        style={{
          width: sizes.icon.lg,
          height: sizes.icon.lg,
          borderRadius: radius.full,
          borderWidth: borderWidth.selected,
          borderColor: selected ? colors.primary : colors.borderStrong,
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        {selected && (
          <View
            style={{
              width: 12,
              height: 12,
              borderRadius: radius.full,
              backgroundColor: colors.primary,
            }}
          />
        )}
      </View>
    </Pressable>
  );
}
