import { LANGUAGES, LANGUAGE_NAMES } from "@beavent/i18n";
import { BottomSheet, Button, CheckRow, OptionRow, Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import {
  addDays,
  AUDIENCES,
  PARTS,
  PRICE_CEILINGS_ETB,
  price,
  RATINGS,
  todayIn,
  withFilters,
  type Audience,
  type Filters,
  type Part,
  type Sort,
} from "@beavent/venue";
import { useEffect, useState } from "react";
import { View } from "react-native";

import { fetchPlaces, MARKET } from "../../lib/places";

const SORTS: Sort[] = ["RECOMMENDED", "NEWEST", "LOWEST_PRICE"];

/**
 * Sort and filters of the results (S-02 filter sheet): choices change a draft, the footer shows how many places that
 * would give and applies it (on a phone, filters apply on confirm). Mount it fresh for each opening (`key`).
 */
export function FilterSheet({
  visible,
  filters,
  scope,
  onApply,
  onClose,
}: {
  visible: boolean;
  filters: Filters;
  /** What the page itself fixes (a category's results), so the count matches. */
  scope: { category?: string };
  onApply: (filters: Filters) => void;
  onClose: () => void;
}) {
  const { api, t, language } = useApp();
  const [draft, setDraft] = useState(filters);
  const [count, setCount] = useState<number | null>(null);
  const set = (patch: Partial<Filters>) => setDraft((d) => withFilters(d, patch));
  const today = todayIn(MARKET.timeZone);
  const tomorrow = addDays(today, 1);

  useEffect(() => {
    if (!visible) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      fetchPlaces(api, draft, { limit: 1, category: scope.category })
        .then((page) => !cancelled && setCount(page.total ?? page.items.length))
        .catch(() => !cancelled && setCount(null));
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [api, draft, scope.category, visible]);

  const heading = (text: string) => (
    <Text role="labelStrong" style={{ marginTop: space[2] }}>
      {text}
    </Text>
  );

  return (
    <BottomSheet
      visible={visible}
      onClose={onClose}
      title={t("filter.label")}
      closeLabel={t("filter.close")}
      footer={
        <View style={{ gap: space[1] }}>
          <Button
            block
            label={count === 0 ? t("filter.show.none") : t("filter.show", { count: count ?? 0 })}
            disabled={count === 0}
            onPress={() => {
              onApply(draft);
              onClose();
            }}
          />
          <Button
            block
            variant="tertiary"
            label={t("filter.clear")}
            onPress={() =>
              setDraft(
                withFilters(draft, {
                  sort: undefined,
                  date: undefined,
                  part: undefined,
                  maxPrice: undefined,
                  minRating: undefined,
                  openNow: undefined,
                  atHome: undefined,
                  audience: undefined,
                  language: undefined,
                  area: undefined,
                }),
              )
            }
          />
        </View>
      }
    >
      {heading(t("filter.sort"))}
      {SORTS.map((s) => (
        <OptionRow
          key={s}
          title={t(`filter.sort.${s}`)}
          selected={(draft.sort ?? "RECOMMENDED") === s}
          onPress={() => set({ sort: s === "RECOMMENDED" ? undefined : s })}
        />
      ))}

      {heading(t("search.when"))}
      <OptionRow
        title={t("search.anyDate")}
        selected={draft.date === undefined}
        onPress={() => set({ date: undefined, part: undefined })}
      />
      <OptionRow
        title={t("filter.when.today")}
        selected={draft.date === today}
        onPress={() => set({ date: today })}
      />
      <OptionRow
        title={t("filter.when.tomorrow")}
        selected={draft.date === tomorrow}
        onPress={() => set({ date: tomorrow })}
      />
      {draft.date ? (
        <>
          <OptionRow
            title={t("search.anyPart")}
            selected={draft.part === undefined}
            onPress={() => set({ part: undefined })}
          />
          {PARTS.map((p) => (
            <OptionRow
              key={p}
              title={t(`booking.time.${p.toLowerCase() as "morning" | "afternoon" | "evening"}`)}
              selected={draft.part === p}
              onPress={() => set({ part: p as Part })}
            />
          ))}
        </>
      ) : null}

      {heading(t("filter.price"))}
      <OptionRow
        title={t("filter.price.any")}
        selected={draft.maxPrice === undefined}
        onPress={() => set({ maxPrice: undefined })}
      />
      {PRICE_CEILINGS_ETB.map((etb) => (
        <OptionRow
          key={etb}
          title={t("filter.price.under", { amount: price(etb * 100, "ETB", language) })}
          selected={draft.maxPrice === etb * 100}
          onPress={() => set({ maxPrice: etb * 100 })}
        />
      ))}

      {heading(t("filter.rating"))}
      <OptionRow
        title={t("filter.rating.any")}
        selected={draft.minRating === undefined}
        onPress={() => set({ minRating: undefined })}
      />
      {RATINGS.map((r) => (
        <OptionRow
          key={r}
          title={t("filter.rating.from", { rating: r })}
          selected={draft.minRating === r}
          onPress={() => set({ minRating: r })}
        />
      ))}

      {heading(t("filter.for"))}
      <OptionRow
        title={t("filter.for.any")}
        selected={draft.audience === undefined}
        onPress={() => set({ audience: undefined })}
      />
      {AUDIENCES.map((a) => (
        <OptionRow
          key={a}
          title={t(`filter.for.${a}`)}
          selected={draft.audience === a}
          onPress={() => set({ audience: a as Audience })}
        />
      ))}

      {heading(t("filter.language"))}
      <OptionRow
        title={t("filter.language.any")}
        selected={draft.language === undefined}
        onPress={() => set({ language: undefined })}
      />
      {LANGUAGES.map((l) => (
        <OptionRow
          key={l}
          title={LANGUAGE_NAMES[l]}
          selected={draft.language === l}
          onPress={() => set({ language: l })}
        />
      ))}

      <CheckRow
        title={t("filter.openNow")}
        checked={draft.openNow === true}
        onPress={() => set({ openNow: draft.openNow ? undefined : true })}
      />
      <CheckRow
        title={t("filter.atHome")}
        checked={draft.atHome === true}
        onPress={() => set({ atHome: draft.atHome ? undefined : true })}
      />
    </BottomSheet>
  );
}
