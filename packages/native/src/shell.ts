import { useFonts } from "expo-font";
import { DarkTheme, DefaultTheme } from "expo-router";
import * as SplashScreen from "expo-splash-screen";
import { useEffect } from "react";

import { useApp } from "./app-context";
import { ETHIOPIC_FONT_FILES } from "./constants/fonts";

void SplashScreen.preventAutoHideAsync();

/**
 * Everything a root layout needs before it renders: whether the app is ready (stored session and preferences loaded
 * and the Ge'ez font available — the splash screen stays up until then), and the navigation theme built from the
 * Beavent tokens. Render nothing while `ready` is false: on the web, device-dependent UI (language, theme, clock)
 * would otherwise differ between server and browser and fail hydration. Call inside <AppProvider>.
 */
export function useBeaventShell() {
  const { ready: appReady, scheme, colors } = useApp();
  // A font that fails to load falls back to the system font rather than blocking the app.
  const [fontsLoaded, fontError] = useFonts(ETHIOPIC_FONT_FILES);
  const ready = appReady && (fontsLoaded || !!fontError);
  useEffect(() => {
    if (ready) void SplashScreen.hideAsync();
  }, [ready]);
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
  return { ready, theme, scheme, colors };
}
