import {
  Button,
  Group,
  ListRow,
  PreferencesSections,
  Screen,
  ScreenTitle,
  SignInPanel,
  SignedInSummary,
  useApp,
} from "@beavent/native";
import { useRouter } from "expo-router";
import { View } from "react-native";

/** More (B-5): who is signed in, "My schedule", display preferences, sign out. Customers and messages join later. */
export default function MoreScreen() {
  const app = useApp();
  const { t, signedIn } = app;
  const router = useRouter();
  return (
    <Screen inTabs>
      <ScreenTitle>{t("nav.more")}</ScreenTitle>
      {signedIn === false && <SignInPanel />}
      <SignedInSummary />
      {signedIn ? (
        <Group>
          <ListRow
            title={t("mobile.more.mySchedule")}
            chevron
            onPress={() => router.push("/schedule")}
          />
        </Group>
      ) : null}
      <PreferencesSections />
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
