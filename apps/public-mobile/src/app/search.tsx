import type { Schemas } from "@beavent/api-client";
import { Button, Chip, ListRow, Text, SearchField, useApp } from "@beavent/native";
import { layout, space } from "@beavent/ui";
import { pick } from "@beavent/venue";
import { useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { ScrollView, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import {
  addRecentSearch,
  clearRecentSearches,
  fetchCategories,
  MARKET,
  recentSearches,
  type Category,
} from "../lib/places";

type Suggestions = Schemas["SearchSuggestions"];
const GEEZ = /[ሀ-ፗ]/;

/**
 * Search (C-2), full screen over the tabs: recent searches and popular categories while empty; suggestions grouped as
 * categories and places while typing (a Ge'ez syllable is enough to start, two Latin letters otherwise). Submitting
 * opens the results. Typing never shows an error: the last good suggestions stay, and "Search for …" always works.
 */
export default function SearchScreen() {
  const { api, t, language, colors } = useApp();
  const router = useRouter();
  const [text, setText] = useState("");
  const [recent, setRecent] = useState<string[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [suggested, setSuggested] = useState<{ text: string; value: Suggestions } | undefined>();
  const typed = text.trim();
  const active = typed.length >= (GEEZ.test(typed) ? 1 : 2);

  useEffect(() => {
    let cancelled = false;
    recentSearches().then((list) => !cancelled && setRecent(list));
    fetchCategories(api)
      .then((list) => !cancelled && setCategories(list))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [api]);

  useEffect(() => {
    if (!active) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      api
        .GET("/v1/public/search/suggestions", {
          params: { query: { q: typed, market: MARKET.code, limit: 3 } },
        })
        .then(({ data }) => !cancelled && data && setSuggested({ text: typed, value: data }))
        .catch(() => undefined);
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [api, active, typed]);

  // Close the search modal first: a screen opened from inside a modal would itself be shown as a modal (iOS).
  const go = (href: Parameters<typeof router.push>[0]) => {
    router.dismiss();
    router.push(href);
  };
  const submit = (q: string) => {
    void addRecentSearch(q);
    go({ pathname: "/results", params: { q } });
  };
  const suggestions = active && suggested ? suggested.value : undefined;
  const heading = (label: string) => (
    <Text role="labelStrong" tone="muted" accessibilityRole="header">
      {label}
    </Text>
  );

  return (
    <SafeAreaView edges={["top"]} style={{ flex: 1, backgroundColor: colors.background }}>
      <View
        style={{
          flexDirection: "row",
          alignItems: "center",
          gap: space[2],
          paddingStart: layout.gutter,
          paddingEnd: space[2],
          paddingTop: space[2],
        }}
      >
        <View style={{ flex: 1 }}>
          <SearchField
            autoFocus
            placeholder={t("search.placeholder")}
            value={text}
            onChangeText={setText}
            onSubmit={() => typed && submit(typed)}
            clearLabel={t("search.clearField")}
          />
        </View>
        <Button
          variant="tertiary"
          size="sm"
          label={t("search.cancel")}
          onPress={() => router.back()}
        />
      </View>
      <ScrollView
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
        contentContainerStyle={{
          alignSelf: "center",
          width: "100%",
          maxWidth: layout.maxContentWidth,
          padding: layout.gutter,
          gap: layout.sectionGap / 2,
        }}
      >
        {active ? (
          <>
            <ListRow title={t("search.for", { q: typed })} onPress={() => submit(typed)} chevron />
            {suggestions && suggestions.categories.length > 0 ? (
              <View style={{ gap: space[1] }}>
                {heading(t("search.categories"))}
                {suggestions.categories.map((c) => (
                  <ListRow
                    key={`${c.kind}-${c.code}`}
                    title={pick(c.name, language)}
                    onPress={() => go({ pathname: "/results", params: { category: c.code } })}
                    chevron
                  />
                ))}
              </View>
            ) : null}
            {suggestions && suggestions.places.length > 0 ? (
              <View style={{ gap: space[1] }}>
                {heading(t("search.places"))}
                {suggestions.places.map((p) => (
                  <ListRow
                    key={p.id}
                    title={p.title}
                    subtitle={[pick(p.category, language), pick(p.area, language)]
                      .filter(Boolean)
                      .join(" · ")}
                    onPress={() => go(`/b/${p.businessSlug}/${p.locationSlug}`)}
                    chevron
                  />
                ))}
              </View>
            ) : null}
          </>
        ) : (
          <>
            {recent.length > 0 ? (
              <View style={{ gap: space[1] }}>
                <View style={{ flexDirection: "row", alignItems: "center" }}>
                  <View style={{ flex: 1 }}>{heading(t("search.recent"))}</View>
                  <Button
                    variant="tertiary"
                    size="sm"
                    label={t("search.clearRecent")}
                    onPress={() => {
                      setRecent([]);
                      void clearRecentSearches();
                    }}
                  />
                </View>
                {recent.map((word) => (
                  <ListRow key={word} title={word} onPress={() => submit(word)} />
                ))}
              </View>
            ) : null}
            {categories.length > 0 ? (
              <View style={{ gap: space[3] }}>
                {heading(t("search.popular"))}
                <View style={{ flexDirection: "row", flexWrap: "wrap", gap: space[2] }}>
                  {categories.map((c) => (
                    <Chip
                      key={c.id}
                      label={pick(c.name, language)}
                      selected={false}
                      onPress={() => go({ pathname: "/results", params: { category: c.code } })}
                    />
                  ))}
                </View>
              </View>
            ) : null}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
