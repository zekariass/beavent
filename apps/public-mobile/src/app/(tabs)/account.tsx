import {
  Button,
  Group,
  ListRow,
  PreferencesSections,
  Screen,
  ScreenTitle,
  Section,
  SignedInSummary,
  Text,
  openWeb,
  useApp,
} from "@beavent/native";
import { space } from "@beavent/ui";
import { useRouter } from "expo-router";
import { View } from "react-native";

/** Account (C-5): who is signed in, display preferences (MARKETPLACE_NAVIGATION.md §7), business link, sign out. */
export default function AccountScreen() {
  const app = useApp();
  const { t, signedIn } = app;
  const router = useRouter();

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
      <SignedInSummary />

      <PreferencesSections />

      <Section title={t("account.forBusiness")}>
        <Group>
          {/* Business tools live in the separate Beavent Business app; the web console is the way in from here. */}
          <ListRow
            title={t("account.business.row")}
            chevron
            onPress={() => void openWeb("/business")}
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
