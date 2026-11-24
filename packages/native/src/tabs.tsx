import { NativeTabs } from "expo-router/native-tabs";

import { useApp } from "./app-context";
import type { BeaventTabsProps } from "./tabs-types";

/** Native tabs with token colours and translated labels (COMPONENT_GUIDELINES.md §5.1). */
export function BeaventTabs({ tabs }: BeaventTabsProps) {
  const { colors, t } = useApp();
  return (
    <NativeTabs
      backgroundColor={colors.background}
      tintColor={colors.primary}
      iconColor={{ default: colors.textMuted, selected: colors.primary }}
      labelStyle={{ default: { color: colors.textMuted }, selected: { color: colors.primary } }}
      indicatorColor={colors.primarySubtle}
    >
      {tabs.map((tab) => (
        <NativeTabs.Trigger key={tab.name} name={tab.name}>
          <NativeTabs.Trigger.Label>{t(tab.label)}</NativeTabs.Trigger.Label>
          {/* Symbol names are checked by the apps' own tab definitions; the library types them as string unions. */}
          <NativeTabs.Trigger.Icon sf={tab.sf as never} md={tab.md as never} />
        </NativeTabs.Trigger>
      ))}
    </NativeTabs>
  );
}
