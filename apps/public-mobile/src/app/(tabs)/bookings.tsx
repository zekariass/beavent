import { useRouter } from "expo-router";

import { EmptyState, Screen, ScreenTitle, SkeletonRows } from "@/components/ui";
import { useApp } from "@/lib/app-context";

/**
 * Bookings (C-4). Appointments and tickets share this tab; until the booking API exists (Stage 2.5) it shows the
 * signed-out, loading and empty states from the spec.
 */
export default function BookingsScreen() {
  const { t, signedIn } = useApp();
  const router = useRouter();
  return (
    <Screen inTabs>
      <ScreenTitle>{t("nav.bookings")}</ScreenTitle>
      {signedIn === null ? (
        <SkeletonRows />
      ) : signedIn ? (
        <EmptyState
          title={t("bookings.empty.title")}
          body={t("bookings.empty.body")}
          action={{ label: t("nav.explore"), onPress: () => router.navigate("/") }}
        />
      ) : (
        <EmptyState
          title={t("bookings.signedOut.title")}
          body={t("bookings.signedOut.body")}
          action={{
            label: t("nav.signIn"),
            variant: "primary",
            onPress: () => router.push("/sign-in"),
          }}
        />
      )}
    </Screen>
  );
}
