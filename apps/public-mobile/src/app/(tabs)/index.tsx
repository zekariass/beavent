import {
  Button,
  Chip,
  EmptyState,
  Icon,
  Screen,
  SearchField,
  Section,
  Skeleton,
  Text,
  useApp,
} from "@beavent/native";
import { layout, space } from "@beavent/ui";
import {
  pick,
  todayIn,
  toRecord,
  type Filters,
  type SearchItem,
  type SearchPage,
} from "@beavent/venue";
import { useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { Pressable, ScrollView, View } from "react-native";

import { LocationSheet } from "../../components/marketplace/location-sheet";
import { CARD_WIDTH, VenueCard } from "../../components/marketplace/venue-card";
import { VenueResultRow } from "../../components/marketplace/venue-result-row";
import {
  fetchCategories,
  fetchCity,
  fetchPlaces,
  MARKET,
  rememberArea,
  rememberedArea,
  type AdminArea,
  type Category,
} from "../../lib/places";

/** Below this many places the groups would only repeat each other: one list of everything instead (DD12). */
const MIN_PLACES_FOR_GROUPS = 8;
const MIN_PER_GROUP = 3;

type Data = {
  city: AdminArea;
  areas: AdminArea[];
  categories: Category[];
  all: SearchPage;
  today: SearchPage;
  atHome: SearchPage;
  newest: SearchPage;
};
type State = { status: "loading" } | { status: "error" } | { status: "ready"; data: Data };

/**
 * Explore (C-1): where, search, categories, and groups of places (or one list while the market is small). Services
 * only for now; the Events scope arrives with Stage 6.
 */
export default function ExploreScreen() {
  const { api, t, language } = useApp();
  const router = useRouter();
  // undefined: not read yet; null: the whole city.
  const [area, setArea] = useState<string | null | undefined>(undefined);
  const [state, setState] = useState<State>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [picking, setPicking] = useState(false);

  useEffect(() => {
    let cancelled = false;
    rememberedArea().then((code) => !cancelled && setArea(code ?? null));
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (area === undefined) return;
    let cancelled = false;
    const where: Filters = area ? { area } : {};
    Promise.all([
      fetchCity(api),
      fetchCategories(api),
      fetchPlaces(api, where, { limit: 20 }),
      fetchPlaces(api, { ...where, date: todayIn(MARKET.timeZone) }, { limit: 4 }),
      fetchPlaces(api, { ...where, atHome: true }, { limit: 4 }),
      fetchPlaces(api, { ...where, sort: "NEWEST" }, { limit: 4 }),
    ])
      .then(([{ city, areas }, categories, all, today, atHome, newest]) => {
        if (!cancelled)
          setState({
            status: "ready",
            data: { city, areas, categories, all, today, atHome, newest },
          });
      })
      .catch(() => !cancelled && setState({ status: "error" }));
    return () => {
      cancelled = true;
    };
  }, [api, area, attempt]);

  const retry = () => {
    setState({ status: "loading" });
    setAttempt((n) => n + 1);
  };
  const choose = (code: string | undefined) => {
    setState({ status: "loading" });
    setArea(code ?? null);
    void rememberArea(code ?? "");
  };
  const open = (item: SearchItem) => router.push(`/b/${item.businessSlug}/${item.locationSlug}`);
  const results = (filters: Filters) =>
    router.push({
      pathname: "/results",
      params: toRecord({ ...filters, area: area ?? undefined }),
    });

  const data = state.status === "ready" ? state.data : undefined;
  const name = (a: AdminArea) => pick(a.nameI18n ?? {}, language) || a.name;
  const cityName = data ? name(data.city) : "";
  const areaName =
    area && data ? name(data.areas.find((a) => a.code === area) ?? data.city) : cityName;

  const total = data ? (data.all.total ?? data.all.items.length) : 0;
  const groups = data
    ? [
        {
          title: t("home.availableToday"),
          page: data.today,
          filters: { date: todayIn(MARKET.timeZone) },
        },
        { title: t("home.atHome"), page: data.atHome, filters: { atHome: true } },
        { title: t("home.newOnBeavent"), page: data.newest, filters: { sort: "NEWEST" as const } },
      ].filter((g) => g.page.items.length >= MIN_PER_GROUP)
    : [];
  const showGroups = total >= MIN_PLACES_FOR_GROUPS && groups.length > 0;

  return (
    <Screen inTabs>
      <View style={{ gap: space[3] }}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`${t("explore.location")}: ${areaName}`}
          onPress={() => data && setPicking(true)}
          style={{ flexDirection: "row", alignItems: "center", gap: space[1], minHeight: 48 }}
        >
          <Text role="labelStrong">{areaName || t("explore.location")}</Text>
          <View style={{ transform: [{ rotate: "90deg" }] }}>
            <Icon name="chevron" size="sm" />
          </View>
        </Pressable>
        <SearchField placeholder={t("search.placeholder")} onPress={() => router.push("/search")} />
      </View>

      {state.status === "loading" ? (
        <View style={{ gap: space[4] }}>
          <View style={{ flexDirection: "row", gap: space[2] }}>
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} width={90} height={36} />
            ))}
          </View>
          <View style={{ flexDirection: "row", gap: layout.railGap }}>
            {[0, 1].map((i) => (
              <View key={i} style={{ gap: space[2] }}>
                <Skeleton width={CARD_WIDTH} height={CARD_WIDTH * (2 / 3)} />
                <Skeleton width={CARD_WIDTH * 0.8} height={16} />
                <Skeleton width={CARD_WIDTH * 0.5} height={14} />
              </View>
            ))}
          </View>
        </View>
      ) : state.status === "error" ? (
        <EmptyState
          title={t("results.error")}
          action={{ label: t("action.retry"), onPress: retry }}
        />
      ) : (
        <>
          {state.data.categories.length >= MIN_PER_GROUP ? (
            <Section title={t("home.browse")}>
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={{ gap: space[2] }}
              >
                {state.data.categories.map((c) => (
                  <Chip
                    key={c.id}
                    label={pick(c.name, language)}
                    selected={false}
                    onPress={() => results({ category: c.code })}
                  />
                ))}
              </ScrollView>
            </Section>
          ) : null}

          {total === 0 ? (
            <EmptyState
              title={t("home.empty.title", { city: areaName })}
              body={t("home.empty.body")}
              action={
                area
                  ? {
                      label: t("search.allOf", { city: cityName }),
                      onPress: () => choose(undefined),
                    }
                  : undefined
              }
            />
          ) : showGroups ? (
            groups.map((g) => (
              <Section
                key={g.title}
                title={g.title}
                action={{ label: t("home.seeAll"), onPress: () => results(g.filters) }}
              >
                <ScrollView
                  horizontal
                  showsHorizontalScrollIndicator={false}
                  contentContainerStyle={{ gap: layout.railGap }}
                >
                  {g.page.items.map((item) => (
                    <VenueCard key={item.id} item={item} onPress={() => open(item)} />
                  ))}
                </ScrollView>
              </Section>
            ))
          ) : (
            <Section title={t("home.allPlaces", { city: areaName })}>
              <View>
                {state.data.all.items.map((item) => (
                  <VenueResultRow
                    key={item.id}
                    item={item}
                    onOpen={() => open(item)}
                    onBook={(serviceId) =>
                      router.push(
                        `/b/${item.businessSlug}/${item.locationSlug}/book?service=${serviceId}`,
                      )
                    }
                  />
                ))}
              </View>
              {total > state.data.all.items.length ? (
                <Button variant="secondary" label={t("home.seeAll")} onPress={() => results({})} />
              ) : null}
            </Section>
          )}
        </>
      )}

      {data ? (
        <LocationSheet
          visible={picking}
          city={data.city}
          areas={data.areas}
          selected={area ?? undefined}
          onSelect={choose}
          onClose={() => setPicking(false)}
        />
      ) : null}
    </Screen>
  );
}
