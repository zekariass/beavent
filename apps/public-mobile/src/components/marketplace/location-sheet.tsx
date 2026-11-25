import { BottomSheet, OptionRow, useApp } from "@beavent/native";
import { pick } from "@beavent/venue";

import type { AdminArea } from "../../lib/places";

/** The area to look in (C-1 location selector): the whole city, or one of its sub-cities. */
export function LocationSheet({
  visible,
  city,
  areas,
  selected,
  onSelect,
  onClose,
}: {
  visible: boolean;
  city: AdminArea;
  areas: AdminArea[];
  /** Area code, or undefined for the whole city. */
  selected: string | undefined;
  onSelect: (code: string | undefined) => void;
  onClose: () => void;
}) {
  const { t, language } = useApp();
  const name = (a: AdminArea) => pick(a.nameI18n ?? {}, language) || a.name;
  return (
    <BottomSheet
      visible={visible}
      onClose={onClose}
      title={t("search.where")}
      closeLabel={t("filter.close")}
    >
      <OptionRow
        title={t("search.allOf", { city: name(city) })}
        selected={selected === undefined}
        onPress={() => {
          onSelect(undefined);
          onClose();
        }}
      />
      {areas.map((area) => (
        <OptionRow
          key={area.id}
          title={name(area)}
          selected={selected === area.code}
          onPress={() => {
            onSelect(area.code);
            onClose();
          }}
        />
      ))}
    </BottomSheet>
  );
}
