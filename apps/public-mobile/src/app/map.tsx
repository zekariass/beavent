import { Button, EmptyState, Skeleton, Text, useApp } from "@beavent/native";
import { layout, radius, space } from "@beavent/ui";
import {
  nextAvailableText,
  parseFilters,
  pick,
  resultMeta,
  resultPrice,
  toRecord,
  type SearchItem,
  type SearchPin,
  type SearchPins,
} from "@beavent/venue";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useMemo, useRef, useState } from "react";
import { FlatList, Pressable, View, useWindowDimensions } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { PlaceImage } from "../components/marketplace/place-image";
import { fitPins, PlacesMap, type MapWindow } from "../components/marketplace/places-map";
import { fetchCategories, fetchPins, fetchPlaces } from "../lib/places";

const GAP = space[3];

type Data = { pins: SearchPins; items: Map<string, SearchItem> };
type State = { status: "loading" } | { status: "error" } | { status: "ready"; data: Data };

/**
 * The map of a search (S-03), full screen under the header: a pill with the "from" price for each place, a strip of
 * place cards below that follows the chosen pin (and moves the pin when swiped), and "Search this area" once the map
 * was moved, which limits the search (and the list) to what the map shows. It takes the same params as Results.
 */
export default function MapScreen() {
  const params = useLocalSearchParams();
  const { api, t, colors, language } = useApp();
  const router = useRouter();
  const { width } = useWindowDimensions();
  const [categoryName, setCategoryName] = useState<string | undefined>();
  const filters = useMemo(
    () => parseFilters(params as Record<string, string | string[] | undefined>),
    [params],
  );
  const key = JSON.stringify(toRecord(filters));
  const [state, setState] = useState<State>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [camera, setCamera] = useState(() => fitPins([]));
  const [moved, setMoved] = useState<MapWindow | null>(null);
  const strip = useRef<FlatList<SearchPin>>(null);
  const cardWidth = Math.min(width - layout.gutter * 2, 420);

  useEffect(() => {
    let cancelled = false;
    Promise.all([fetchPins(api, filters), fetchPlaces(api, filters, { limit: 50 })])
      .then(([pins, page]) => {
        if (cancelled) return;
        setState({
          status: "ready",
          data: { pins, items: new Map(page.items.map((item) => [item.id, item])) },
        });
        setSelectedId(undefined);
        setMoved(null);
        // A map window the person chose stays as it is; otherwise show every place.
        if (!filters.bbox) setCamera(fitPins(pins.pins));
      })
      .catch(() => !cancelled && setState({ status: "error" }));
    return () => {
      cancelled = true;
    };
    // `key` stands for the filters' content.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, key, attempt]);

  useEffect(() => {
    if (!filters.category) return;
    let cancelled = false;
    fetchCategories(api)
      .then((all) => {
        const found = all.find((c) => c.code === filters.category);
        if (!cancelled && found) setCategoryName(pick(found.name, language));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [api, filters.category, language]);

  const reload = () => {
    setState({ status: "loading" });
    setAttempt((n) => n + 1);
  };
  const searchHere = () => {
    if (!moved) return;
    const bbox = [moved.west, moved.south, moved.east, moved.north]
      .map((n) => Math.round(n * 1e5) / 1e5)
      .join(",");
    setState({ status: "loading" });
    router.setParams({ ...toRecord(filters), bbox });
  };

  const pins = state.status === "ready" ? state.data.pins.pins : [];
  const select = (id: string, scroll: boolean) => {
    setSelectedId(id);
    const index = pins.findIndex((p) => p.id === id);
    const pin = pins[index];
    if (pin)
      setCamera((c) => ({
        coordinates: { latitude: pin.latitude, longitude: pin.longitude },
        zoom: c.zoom,
      }));
    if (scroll && index >= 0) strip.current?.scrollToIndex({ index, animated: true });
  };
  const open = (pin: SearchPin) => {
    const [, business, location] = pin.path.split("/");
    router.push(`/b/${business}/${location}`);
  };

  return (
    <SafeAreaView edges={[]} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen options={{ title: filters.q ?? categoryName ?? t("map.show") }} />
      {state.status === "error" ? (
        <View style={{ padding: layout.gutter }}>
          <EmptyState
            title={t("results.error")}
            action={{ label: t("action.retry"), onPress: reload }}
          />
        </View>
      ) : (
        <View style={{ flex: 1 }}>
          <PlacesMap
            pins={pins}
            selectedId={selectedId}
            camera={camera}
            onSelect={(id) => select(id, true)}
            onMoved={setMoved}
          />
          <View
            pointerEvents="box-none"
            style={{
              position: "absolute",
              top: space[3],
              start: 0,
              end: 0,
              flexDirection: "row",
              justifyContent: "center",
            }}
          >
            {moved ? (
              <Button variant="secondary" label={t("map.searchArea")} onPress={searchHere} />
            ) : null}
          </View>
          <View
            pointerEvents="box-none"
            style={{ position: "absolute", bottom: space[4], start: 0, end: 0, gap: space[3] }}
          >
            <View style={{ flexDirection: "row", justifyContent: "center" }}>
              <Button variant="secondary" label={t("map.list")} onPress={() => router.back()} />
            </View>
            {state.status === "loading" ? (
              <View style={{ paddingHorizontal: layout.gutter }}>
                <Skeleton height={104} />
              </View>
            ) : pins.length === 0 ? (
              <Text
                role="label"
                style={{
                  alignSelf: "center",
                  backgroundColor: colors.background,
                  paddingHorizontal: space[3],
                  paddingVertical: space[2],
                  borderRadius: radius.lg,
                }}
              >
                {t("map.noPlaces")}
              </Text>
            ) : (
              <FlatList
                ref={strip}
                horizontal
                data={pins}
                keyExtractor={(pin) => pin.id}
                showsHorizontalScrollIndicator={false}
                snapToInterval={cardWidth + GAP}
                decelerationRate="fast"
                contentContainerStyle={{ paddingHorizontal: layout.gutter, gap: GAP }}
                getItemLayout={(_, index) => ({
                  length: cardWidth + GAP,
                  offset: (cardWidth + GAP) * index,
                  index,
                })}
                onMomentumScrollEnd={(event) => {
                  const pin =
                    pins[Math.round(event.nativeEvent.contentOffset.x / (cardWidth + GAP))];
                  if (pin && pin.id !== selectedId) select(pin.id, false);
                }}
                renderItem={({ item: pin }) => (
                  <MapCard
                    pin={pin}
                    item={state.status === "ready" ? state.data.items.get(pin.id) : undefined}
                    width={cardWidth}
                    selected={pin.id === selectedId}
                    onPress={() => open(pin)}
                  />
                )}
              />
            )}
          </View>
        </View>
      )}
    </SafeAreaView>
  );
}

/** A compact place card for the strip: photo, name, area, price or next free time. */
function MapCard({
  pin,
  item,
  width,
  selected,
  onPress,
}: {
  pin: SearchPin;
  item: SearchItem | undefined;
  width: number;
  selected: boolean;
  onPress: () => void;
}) {
  const { t, language, colors } = useApp();
  const meta = item ? resultMeta(item, language, t) : "";
  const next = item ? nextAvailableText(item, language, t) : null;
  const from = item ? resultPrice(item, language, t) : null;
  return (
    <Pressable
      accessibilityRole="link"
      accessibilityLabel={[pin.title, meta, from].filter(Boolean).join(", ")}
      onPress={onPress}
      style={{
        width,
        flexDirection: "row",
        gap: space[3],
        padding: space[3],
        borderRadius: radius.lg,
        borderWidth: selected ? 1.5 : 1,
        borderColor: selected ? colors.primary : colors.border,
        backgroundColor: colors.background,
      }}
    >
      <PlaceImage
        uri={item?.imageVariants.w320 ?? item?.imageUrl}
        name={pin.title}
        width={88}
        height={88}
      />
      <View style={{ flex: 1, gap: space[1] }}>
        <Text role="bodyStrong" lines={2}>
          {pin.title}
        </Text>
        {meta ? (
          <Text role="label" tone="muted" lines={1}>
            {meta}
          </Text>
        ) : null}
        {next ? (
          <Text role="label" lines={2}>
            {next}
          </Text>
        ) : from ? (
          <Text role="label">{from}</Text>
        ) : null}
      </View>
    </Pressable>
  );
}
