import { Button, Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { nextAvailableText, resultMeta, type SearchItem } from "@beavent/venue";
import { Pressable, View } from "react-native";

import { RatingLine } from "../reviews/rating-line";
import { HeartButton } from "./heart-button";
import { PlaceImage } from "./place-image";

export const CARD_WIDTH = 168;

/** A place in a rail (S-01 VenueCard): image-led and tappable, so a card; no border or background (DD9). */
export function VenueCard({
  item,
  onPress,
  heart,
  extra,
  action,
}: {
  item: SearchItem;
  onPress: () => void;
  heart?: { saved: boolean; onToggle: () => void };
  /** A line under the card's own ("Last: Box braids · with Hana"). */
  extra?: string;
  action?: { label: string; onPress: () => void };
}) {
  const { t, language } = useApp();
  const next = nextAvailableText(item, language, t);
  const meta = resultMeta(item, language, t);
  return (
    <View style={{ width: CARD_WIDTH, gap: space[2] }}>
      <Pressable
        accessibilityRole="link"
        accessibilityLabel={[item.title, meta, next].filter(Boolean).join(", ")}
        onPress={onPress}
        style={({ pressed }) => ({ gap: space[2], opacity: pressed ? 0.8 : 1 })}
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
          <RatingLine average={item.ratingAvg} count={item.ratingCount} />
          {next ? (
            <Text role="label" lines={2}>
              {next}
            </Text>
          ) : null}
          {extra ? (
            <Text role="label" tone="muted" lines={2}>
              {extra}
            </Text>
          ) : null}
        </View>
      </Pressable>
      {heart && item.locationId ? (
        <View style={{ position: "absolute", top: space[1], end: space[1] }}>
          <HeartButton name={item.title} saved={heart.saved} onPress={heart.onToggle} onImage />
        </View>
      ) : null}
      {action ? (
        <View style={{ flexDirection: "row" }}>
          <Button variant="secondary" size="sm" label={action.label} onPress={action.onPress} />
        </View>
      ) : null}
    </View>
  );
}
