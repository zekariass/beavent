import { LANGUAGE_NAMES, LANGUAGES } from "@beavent/i18n";
import { space } from "@beavent/ui";
import { useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { View } from "react-native";

import {
  Button,
  Group,
  ListRow,
  OptionRow,
  Screen,
  ScreenTitle,
  Section,
  Skeleton,
  Text,
} from "@/components/ui";
import { useApp, type Appearance } from "@/lib/app-context";

/** Account (C-5): who is signed in, display preferences (MARKETPLACE_NAVIGATION.md §7), business tools, sign out. */
export default function AccountScreen() {
  const app = useApp();
  const { t, signedIn, api, display } = app;
  const router = useRouter();
  const [who, setWho] = useState<{ name?: string; contact?: string } | null>(null);

  useEffect(() => {
    if (!signedIn) return;
    let cancelled = false;
    void api.GET("/v1/me").then(({ data }) => {
      if (cancelled || !data) return;
      setWho({
        name: data.displayName ?? data.firstName,
        contact: data.phone ?? data.email,
      });
    });
    return () => {
      cancelled = true;
    };
  }, [signedIn, api]);

  const appearances: { value: Appearance; label: string }[] = [
    { value: "system", label: t("account.appearance.system") },
    { value: "light", label: t("account.appearance.light") },
    { value: "dark", label: t("account.appearance.dark") },
  ];

  return (
    <Screen inTabs>
      <ScreenTitle>{t("nav.account")}</ScreenTitle>

      {signedIn === false && (
        <View style={{ gap: space[3], alignItems: "flex-start" }}>
          <Text role="title2">{t("account.signedOut.title")}</Text>
          <Text role="body" tone="muted">
            {t("account.signedOut.body")}
          </Text>
          <Button label={t("nav.signIn")} onPress={() => router.push("/sign-in")} />
        </View>
      )}
      {signedIn && (
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
      )}

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

      <Section title={t("account.forBusiness")}>
        <Group>
          <ListRow
            title={t("mobile.schedule.title")}
            chevron
            onPress={() => router.push("/business")}
          />
        </Group>
      </Section>

      {signedIn && (
        <View style={{ alignItems: "center" }}>
          <Button
            variant="destructiveTertiary"
            label={t("mobile.signOut")}
            onPress={() => void app.signOut()}
          />
        </View>
      )}
    </Screen>
  );
}
