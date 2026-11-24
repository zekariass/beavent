import { layout, space } from "@beavent/ui";
import { ScrollView, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { useBrand } from "@/lib/brand";

/** Placeholder home for Beavent Business until Business mode moves here (see apps/business-mobile/README.md). */
export default function HomeScreen() {
  const { colors, t, text } = useBrand();
  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.background }}>
      <ScrollView
        contentContainerStyle={{
          alignSelf: "center",
          width: "100%",
          maxWidth: layout.maxContentWidth,
          padding: layout.gutter,
          gap: space[3],
        }}
      >
        <View accessibilityRole="header">
          <Text style={[text("title1"), { color: colors.text }]}>{t("business.title")}</Text>
        </View>
        <Text style={[text("body"), { color: colors.textMuted }]}>{t("business.subtitle")}</Text>
      </ScrollView>
    </SafeAreaView>
  );
}
