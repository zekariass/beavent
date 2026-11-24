import { AppProvider, useApp, useBeaventShell } from "@beavent/native";
import { Stack, ThemeProvider } from "expo-router";
import { StatusBar } from "expo-status-bar";

export default function RootLayout() {
  return (
    <AppProvider>
      <Shell />
    </AppProvider>
  );
}

/** Root stack: the consumer tabs, plus the sign-in modal (DD3). */
function Shell() {
  const { t } = useApp();
  const { ready, theme, scheme, colors } = useBeaventShell();
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
        <Stack.Screen name="sign-in" options={{ presentation: "modal", title: t("nav.signIn") }} />
      </Stack>
    </ThemeProvider>
  );
}
