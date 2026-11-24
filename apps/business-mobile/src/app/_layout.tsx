import { AppProvider, useBeaventShell } from "@beavent/native";
import { Stack, ThemeProvider } from "expo-router";
import { StatusBar } from "expo-status-bar";

/** Root of Beavent Business (owners, staff and organizers); shell, theme and session come from @beavent/native. */
export default function RootLayout() {
  return (
    <AppProvider>
      <Shell />
    </AppProvider>
  );
}

function Shell() {
  const { ready, theme, scheme, colors } = useBeaventShell();
  if (!ready) return null;
  return (
    <ThemeProvider value={theme}>
      <StatusBar style={scheme === "dark" ? "light" : "dark"} />
      <Stack
        screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }}
      />
    </ThemeProvider>
  );
}
