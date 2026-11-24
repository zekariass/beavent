import { useRouter } from "expo-router";
import { useEffect } from "react";

import { SignInPanel } from "@/components/sign-in-panel";
import { Screen, Text } from "@/components/ui";
import { useApp } from "@/lib/app-context";

/** Sign-in (C-6) as a modal: closes itself once the person is signed in, returning to what they were doing. */
export default function SignInScreen() {
  const { t, signedIn } = useApp();
  const router = useRouter();
  useEffect(() => {
    if (signedIn && router.canGoBack()) router.back();
  }, [signedIn, router]);
  return (
    <Screen edges={[]}>
      <Text role="body" tone="muted">
        {t("mobile.signIn.hint")}
      </Text>
      <SignInPanel />
    </Screen>
  );
}
