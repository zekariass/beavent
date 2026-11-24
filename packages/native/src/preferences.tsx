import { LANGUAGE_NAMES, LANGUAGES } from "@beavent/i18n";
import { space } from "@beavent/ui";
import { useEffect, useState } from "react";
import { View } from "react-native";

import { useApp, type Appearance } from "./app-context";
import { Skeleton } from "./ui/feedback";
import { Group, OptionRow } from "./ui/list-row";
import { Section } from "./ui/screen";
import { Text } from "./ui/text";

/**
 * Display preferences as option groups (MARKETPLACE_NAVIGATION.md §7): language, calendar, time convention and
 * appearance. Used by Account in the consumer app and More in the business app; changes apply app-wide and persist.
 */
export function PreferencesSections() {
  const app = useApp();
  const { t, display } = app;
  const appearances: { value: Appearance; label: string }[] = [
    { value: "system", label: t("account.appearance.system") },
    { value: "light", label: t("account.appearance.light") },
    { value: "dark", label: t("account.appearance.dark") },
  ];
  return (
    <>
      <Section title={t("language.label")}>
        <Group>
          {LANGUAGES.map((language) => (
            <OptionRow
              key={language}
              title={LANGUAGE_NAMES[language]}
              selected={app.language === language}
              onPress={() => app.setLanguage(language)}
            />
          ))}
        </Group>
      </Section>

      <Section title={t("account.calendar")}>
        <Group>
          <OptionRow
            title={t("calendar.ethiopian")}
            selected={display.calendar === "ETHIOPIAN"}
            onPress={() => app.setCalendar("ETHIOPIAN")}
          />
          <OptionRow
            title={t("calendar.gregorian")}
            selected={display.calendar === "GREGORIAN"}
            onPress={() => app.setCalendar("GREGORIAN")}
          />
        </Group>
      </Section>

      <Section title={t("account.time")}>
        <Group>
          <OptionRow
            title={t("time.ethiopian")}
            selected={display.timeConvention === "ETHIOPIAN"}
            onPress={() => app.setTimeConvention("ETHIOPIAN")}
          />
          <OptionRow
            title={t("time.standard")}
            selected={display.timeConvention === "STANDARD"}
            onPress={() => app.setTimeConvention("STANDARD")}
          />
        </Group>
      </Section>

      <Section title={t("account.appearance")}>
        <Group>
          {appearances.map((option) => (
            <OptionRow
              key={option.value}
              title={option.label}
              selected={app.appearance === option.value}
              onPress={() => app.setAppearance(option.value)}
            />
          ))}
        </Group>
      </Section>
    </>
  );
}

/** Who is signed in: name and phone/email from `/v1/me`, with a skeleton while it loads. Renders nothing signed out. */
export function SignedInSummary() {
  const { t, signedIn, api } = useApp();
  const [who, setWho] = useState<{ name?: string; contact?: string } | null>(null);
  useEffect(() => {
    if (!signedIn) return;
    let cancelled = false;
    void api.GET("/v1/me").then(({ data }) => {
      if (cancelled || !data) return;
      setWho({ name: data.displayName ?? data.firstName, contact: data.phone ?? data.email });
    });
    return () => {
      cancelled = true;
    };
  }, [signedIn, api]);
  if (!signedIn) return null;
  return (
    <View style={{ gap: space[1] }}>
      {who ? (
        <>
          <Text role="title2">{who.name ?? t("account.signedIn")}</Text>
          {who.contact && (
            <Text role="label" tone="muted" numeric>
              {who.contact}
            </Text>
          )}
        </>
      ) : (
        <Skeleton width="50%" height={24} />
      )}
    </View>
  );
}
