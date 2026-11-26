import { radius } from "@beavent/ui";
import { Image } from "expo-image";
import { View, type DimensionValue } from "react-native";

import { Text, useApp } from "@beavent/native";

/** A place's photo, or its first letter on a quiet surface. Decorative: the text next to it names the place. */
export function PlaceImage({
  uri,
  name,
  width,
  height,
  aspectRatio,
}: {
  uri?: string;
  name: string;
  width: DimensionValue;
  height?: number;
  aspectRatio?: number;
}) {
  const { colors } = useApp();
  const box = {
    width,
    height,
    aspectRatio,
    borderRadius: radius.lg,
    backgroundColor: colors.surface,
  };
  return uri ? (
    <Image source={{ uri }} style={box} contentFit="cover" accessible={false} />
  ) : (
    <View style={[box, { alignItems: "center", justifyContent: "center" }]} accessible={false}>
      <Text role="title2" tone="muted">
        {name.slice(0, 1)}
      </Text>
    </View>
  );
}
