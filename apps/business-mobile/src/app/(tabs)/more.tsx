import {
  Button,
  PreferencesSections,
  Screen,
  ScreenTitle,
  SignInPanel,
  SignedInSummary,
  useApp,
} from "@beavent/native";
import { View } from "react-native";

/** More: who is signed in, display preferences, sign out. Customers, messages and settings join here later. */
export default function MoreScreen() {
  const app = useApp();
  const { t, signedIn } = app;
  return (
    <Screen inTabs>
      <ScreenTitle>{t("nav.more")}</ScreenTitle>
      {signedIn === false && <SignInPanel />}
      <SignedInSummary />
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
