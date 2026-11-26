import { Button, Chip, Divider, EmptyState, Skeleton, Text, useApp } from "@beavent/native";
import { layout, space } from "@beavent/ui";
import {
  activeFilterChips,
  activeFilterCount,
  parseFilters,
  pick,
  toRecord,
  withFilters,
  type Filters,
  type SearchItem,
} from "@beavent/venue";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ActivityIndicator, FlatList, ScrollView, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { FilterSheet } from "../components/marketplace/filter-sheet";
import { VenueResultRow } from "../components/marketplace/venue-result-row";
import { useSaved } from "../hooks/use-saved";
import { fetchCategories, fetchPlaces } from "../lib/places";

type State =
  | { status: "loading" }
  | { status: "error" }
  | {
      status: "ready";
      items: SearchItem[];
      total: number;
      cursor: string | undefined;
      more: "idle" | "loading" | "error";
    };

/**
 * Results and category pages (S-02): what matches, with sort and filters in a sheet, newest page appended as the list
 * is scrolled. The search is in the route's params (`?q=braids&category=BRAIDS&maxPrice=100000`), so a result page can
 * be reopened from a link.
 */
export default function ResultsScreen() {
  const params = useLocalSearchParams();
  const { api, t, language, colors } = useApp();
  const router = useRouter();
  const saved = useSaved();
  const filters = useMemo(
    () => parseFilters(params as Record<string, string | string[] | undefined>),
    [params],
  );
  const key = JSON.stringify(toRecord(filters));
  const [state, setState] = useState<State>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [sheet, setSheet] = useState({ open: false, count: 0 });
  const [categoryName, setCategoryName] = useState<string | undefined>();

  useEffect(() => {
    let cancelled = false;
    fetchPlaces(api, filters)
      .then((page) => {
        if (cancelled) return;
        setState({
          status: "ready",
          items: page.items,
          total: page.total ?? page.items.length,
          cursor: page.nextCursor,
          more: "idle",
        });
      })
      .catch(() => !cancelled && setState({ status: "error" }));
    return () => {
      cancelled = true;
    };
    // `key` stands for the filters' content; the object itself changes with every render of the params.
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
  const apply = (next: Filters) => {
    if (JSON.stringify(toRecord(next)) === key) return;
    setState({ status: "loading" });
    router.setParams({
      q: undefined,
      category: undefined,
      area: undefined,
      sort: undefined,
      date: undefined,
      part: undefined,
      maxPrice: undefined,
      minRating: undefined,
      openNow: undefined,
      atHome: undefined,
      audience: undefined,
      language: undefined,
      ...toRecord(next),
    });
  };
  const loadMore = useCallback(() => {
    if (state.status !== "ready" || !state.cursor || state.more === "loading") return;
    const cursor = state.cursor;
    setState({ ...state, more: "loading" });
    fetchPlaces(api, filters, { cursor })
      .then((page) =>
        setState((s) =>
          s.status === "ready"
            ? { ...s, items: [...s.items, ...page.items], cursor: page.nextCursor, more: "idle" }
            : s,
        ),
      )
      .catch(() => setState((s) => (s.status === "ready" ? { ...s, more: "error" } : s)));
  }, [api, filters, state]);

  const open = (item: SearchItem) => router.push(`/b/${item.businessSlug}/${item.locationSlug}`);
  const book = (item: SearchItem, serviceId: string) =>
    router.push(`/b/${item.businessSlug}/${item.locationSlug}/book?service=${serviceId}`);
  const count = activeFilterCount(filters) + (filters.area ? 1 : 0);
  const title = filters.q ?? categoryName ?? filters.category ?? t("search.title");
  const scope = { category: filters.category };

  const header = (
    <View style={{ gap: space[3], paddingBottom: space[2] }}>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ gap: space[2] }}
      >
        <Chip
          label={count > 0 ? `${t("filter.label")} (${count})` : t("filter.label")}
          selected={count > 0}
          onPress={() => setSheet((s) => ({ open: true, count: s.count + 1 }))}
        />
        <Chip
          label={t("filter.openNow")}
          selected={filters.openNow === true}
          onPress={() =>
            apply(withFilters(filters, { openNow: filters.openNow ? undefined : true }))
          }
        />
        <Chip
          label={t("filter.atHome")}
          selected={filters.atHome === true}
          onPress={() => apply(withFilters(filters, { atHome: filters.atHome ? undefined : true }))}
        />
      </ScrollView>
      {state.status === "ready" ? (
        <Text role="label" tone="muted" accessibilityLiveRegion="polite">
          {t("results.count", { count: state.total })}
        </Text>
      ) : null}
    </View>
  );

  return (
    <SafeAreaView edges={[]} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen options={{ title }} />
      <FilterSheet
        key={sheet.count}
        visible={sheet.open}
        filters={filters}
        scope={scope}
        onApply={apply}
        onClose={() => setSheet((s) => ({ ...s, open: false }))}
      />
      {state.status === "ready" && state.items.length > 0 ? (
        <View
          pointerEvents="box-none"
          style={{
            position: "absolute",
            bottom: space[6],
            start: 0,
            end: 0,
            flexDirection: "row",
            justifyContent: "center",
            zIndex: 1,
          }}
        >
          <Button
            variant="secondary"
            label={t("map.show")}
            onPress={() => router.push({ pathname: "/map", params: toRecord(filters) })}
          />
        </View>
      ) : null}
      {state.status === "loading" ? (
        <View style={{ padding: layout.gutter, gap: space[4] }}>
          {header}
          {[0, 1, 2, 3, 4].map((i) => (
            <View key={i} style={{ flexDirection: "row", gap: space[4] }}>
              <Skeleton width={104} height={104} />
              <View style={{ flex: 1, gap: space[2] }}>
                <Skeleton height={20} width="70%" />
                <Skeleton height={16} width="50%" />
                <Skeleton height={16} width="35%" />
              </View>
            </View>
          ))}
        </View>
      ) : state.status === "error" ? (
        <View style={{ padding: layout.gutter }}>
          <EmptyState
            title={t("results.error")}
            action={{ label: t("action.retry"), onPress: reload }}
          />
        </View>
      ) : (
        <FlatList
          data={state.items}
          keyExtractor={(item) => item.id}
          ListHeaderComponent={header}
          contentContainerStyle={{
            alignSelf: "center",
            width: "100%",
            maxWidth: layout.maxContentWidth,
            paddingHorizontal: layout.gutter,
            paddingTop: space[3],
            paddingBottom: layout.sectionGap,
          }}
          renderItem={({ item }) => (
            <VenueResultRow
              item={item}
              heart={{
                saved: saved.isSaved(item.locationId),
                onToggle: () => saved.toggle(item.locationId),
              }}
              onOpen={() => open(item)}
              onBook={(serviceId) => book(item, serviceId)}
            />
          )}
          onEndReached={loadMore}
          onEndReachedThreshold={0.6}
          ListEmptyComponent={
            <View style={{ gap: space[4], paddingTop: space[4] }}>
              <EmptyState
                title={t("results.empty.title")}
                body={t("results.empty.body")}
                action={
                  count > 0 || filters.q
                    ? {
                        label: t("filter.clear"),
                        onPress: () => apply({ category: filters.category }),
                      }
                    : undefined
                }
              />
              <View style={{ flexDirection: "row", flexWrap: "wrap", gap: space[2] }}>
                {activeFilterChips(filters, language, t).map((chip) => (
                  <Chip
                    key={chip.key}
                    selected
                    label={`${chip.label} ×`}
                    onPress={() => apply(withFilters(filters, chip.remove))}
                  />
                ))}
              </View>
            </View>
          }
          ListFooterComponent={
            state.more === "loading" ? (
              <ActivityIndicator color={colors.primary} style={{ paddingVertical: space[4] }} />
            ) : state.more === "error" ? (
              <View style={{ paddingVertical: space[4], gap: space[2] }}>
                <Text role="label" tone="danger">
                  {t("results.moreError")}
                </Text>
                <Chip label={t("action.retry")} selected={false} onPress={loadMore} />
              </View>
            ) : !state.cursor && state.items.length >= 1 ? (
              <View style={{ paddingVertical: space[6] }}>
                <Divider inset={0} />
                <Text
                  role="label"
                  tone="muted"
                  style={{ textAlign: "center", paddingTop: space[4] }}
                >
                  {t("results.end", { count: state.total })}
                </Text>
              </View>
            ) : undefined
          }
        />
      )}
    </SafeAreaView>
  );
}
