import { useFonts } from "expo-font";
import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from "expo-router";
import * as SplashScreen from "expo-splash-screen";
import { StatusBar } from "expo-status-bar";
import { useEffect } from "react";

import { ETHIOPIC_FONT_FILES } from "@/constants/fonts";
import { AppProvider, useApp } from "@/lib/app-context";

void SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  return (
    <AppProvider>
      <Shell />
    </AppProvider>
  );
}

/** Root stack: the consumer tabs, plus screens pushed over them (Business schedule) and the sign-in modal (DD3). */
function Shell() {
  const app = useApp();
  const { scheme, colors, t } = app;
  // A font that fails to load falls back to the system font rather than blocking the app.
  const [fontsLoaded, fontError] = useFonts(ETHIOPIC_FONT_FILES);
  const ready = app.ready && (fontsLoaded || !!fontError);
  useEffect(() => {
    if (ready) void SplashScreen.hideAsync();
  }, [ready]);
  // Nothing renders until the stored preferences, session and Ge'ez font are ready: the splash screen covers this on phones,
  // and on the web it keeps server-rendered HTML identical to the first client render (language, theme and the
  // clock differ between server and browser, which would otherwise fail hydration).
  if (!ready) return null;
  const base = scheme === "dark" ? DarkTheme : DefaultTheme;
  const theme = {
    ...base,
    colors: {
      ...base.colors,
      background: colors.background,
      card: colors.background,
      text: colors.text,
      border: colors.border,
      primary: colors.primary,
      notification: colors.danger,
    },
  };
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
        <Stack.Screen name="business" options={{ title: t("mobile.schedule.title") }} />
        <Stack.Screen name="sign-in" options={{ presentation: "modal", title: t("nav.signIn") }} />
      </Stack>
    </ThemeProvider>
  );
}
