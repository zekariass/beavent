import { formatDuration } from "@beavent/i18n";
import { Divider, Text, useApp } from "@beavent/native";
import { size, space } from "@beavent/ui";
import {
  menuLinePrice,
  nextAvailableText,
  pick,
  resultMeta,
  resultPrice,
  type SearchItem,
} from "@beavent/venue";
import { Pressable, View } from "react-native";

import { HeartButton } from "./heart-button";
import { PlaceImage } from "./place-image";

const THUMB = 104;

/**
 * One place in a result list (S-02 VenueResultRow): thumbnail, name, area and distance, the next free time and up to
 * three matching services. Tapping the place opens it; tapping a service opens booking with that service chosen.
 */
export function VenueResultRow({
  item,
  onOpen,
  onBook,
  heart,
}: {
  item: SearchItem;
  onOpen: () => void;
  onBook: (serviceId: string) => void;
  heart?: { saved: boolean; onToggle: () => void };
}) {
  const { t, language } = useApp();
  const next = nextAvailableText(item, language, t);
  const price = item.menu.length === 0 ? resultPrice(item, language, t) : null;
  const meta = resultMeta(item, language, t);
  const more = item.serviceCount - item.menu.length;
  return (
    <View>
      <View style={{ flexDirection: "row", gap: space[4], paddingVertical: space[4] }}>
        <Pressable
          accessibilityRole="link"
          accessibilityLabel={[item.title, meta, next, price].filter(Boolean).join(", ")}
          onPress={onOpen}
          style={{ flexDirection: "row", gap: space[4], flex: 1 }}
        >
          <PlaceImage
            uri={item.imageVariants.w320 ?? item.imageUrl}
            name={item.title}
            width={THUMB}
            height={THUMB}
          />
          <View style={{ flex: 1, gap: space[1] }}>
            <View style={{ flexDirection: "row", alignItems: "flex-start", gap: space[2] }}>
              <Text role="bodyStrong" lines={2} style={{ flex: 1 }}>
                {item.title}
              </Text>
              {heart && item.locationId ? (
                <View style={{ marginTop: -space[3], marginEnd: -space[3] }}>
                  <HeartButton name={item.title} saved={heart.saved} onPress={heart.onToggle} />
                </View>
              ) : null}
            </View>
            <Text role="label" tone="muted">
              {meta}
            </Text>
            {next ? <Text role="label">{next}</Text> : null}
            {price ? <Text role="label">{price}</Text> : null}
          </View>
        </Pressable>
      </View>
      {item.menu.length > 0 ? (
        <View style={{ paddingStart: THUMB + space[4], paddingBottom: space[3] }}>
          {item.menu.map((line) => (
            <Pressable
              key={line.serviceId}
              accessibilityRole="button"
              onPress={() => onBook(line.serviceId)}
              style={{
                minHeight: size.touchTarget,
                flexDirection: "row",
                alignItems: "center",
                gap: space[3],
                paddingVertical: space[1],
              }}
            >
              <View style={{ flex: 1 }}>
                <Text role="label">{pick(line.name, language)}</Text>
                <Text role="caption" tone="muted">
                  {formatDuration(line.durationMinutes, t)}
                </Text>
              </View>
              <Text role="labelStrong" numeric>
                {menuLinePrice(line, item.currency ?? "ETB", language, t)}
              </Text>
            </Pressable>
          ))}
          {more > 0 ? (
            <Pressable
              accessibilityRole="link"
              onPress={onOpen}
              style={{ minHeight: size.touchTarget, justifyContent: "center" }}
            >
              <Text role="label" tone="primary">
                {t("results.seeAllServices", { count: item.serviceCount })}
              </Text>
            </Pressable>
          ) : null}
        </View>
      ) : null}
      <Divider inset={0} />
    </View>
  );
}
