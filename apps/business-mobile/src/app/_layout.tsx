import { AppProvider, useApp, useBeaventShell } from "@beavent/native";
import { Stack, ThemeProvider } from "expo-router";
import { StatusBar } from "expo-status-bar";

/** Root of Beavent Business (owners, staff and organizers); shell, theme and session come from @beavent/native. */
export default function RootLayout() {
  return (
    <AppProvider clientApp="BUSINESS_APP">
      <Shell />
    </AppProvider>
  );
}

function Shell() {
  const { ready, theme, scheme, colors } = useBeaventShell();
  const { t } = useApp();
  if (!ready) return null;
  return (
    <ThemeProvider value={theme}>
      <StatusBar style={scheme === "dark" ? "light" : "dark"} />
      <Stack
        screenOptions={{
          headerTintColor: colors.primary,
          headerTitleStyle: { color: colors.text },
          headerStyle: { backgroundColor: colors.background },
          headerShadowVisible: false,
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen
          name="walk-in"
          options={{ presentation: "modal", title: t("mobile.walkIn.title") }}
        />
      </Stack>
    </ThemeProvider>
  );
}
