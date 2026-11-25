import { Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { nextAvailableText, resultMeta, type SearchItem } from "@beavent/venue";
import { Pressable, View } from "react-native";

import { PlaceImage } from "./place-image";

export const CARD_WIDTH = 168;

/** A place in a rail (S-01 VenueCard): image-led and tappable, so a card; no border or background (DD9). */
export function VenueCard({ item, onPress }: { item: SearchItem; onPress: () => void }) {
  const { t, language } = useApp();
  const next = nextAvailableText(item, language, t);
  const meta = resultMeta(item, language, t);
  return (
    <Pressable
      accessibilityRole="link"
      accessibilityLabel={[item.title, meta, next].filter(Boolean).join(", ")}
      onPress={onPress}
      style={({ pressed }) => ({ width: CARD_WIDTH, gap: space[2], opacity: pressed ? 0.8 : 1 })}
    >
      <PlaceImage
        uri={item.imageVariants.w960 ?? item.imageUrl}
        name={item.title}
        width={CARD_WIDTH}
        aspectRatio={3 / 2}
      />
      <View style={{ gap: space[1] }}>
        <Text role="bodyStrong" lines={2}>
          {item.title}
        </Text>
        <Text role="label" tone="muted" lines={1}>
          {meta}
        </Text>
        {next ? (
          <Text role="label" lines={2}>
            {next}
          </Text>
        ) : null}
      </View>
    </Pressable>
  );
}
