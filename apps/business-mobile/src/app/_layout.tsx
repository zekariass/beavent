import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from "expo-router";
import * as SplashScreen from "expo-splash-screen";
import { StatusBar } from "expo-status-bar";
import { useEffect } from "react";

import { useBrand } from "@/lib/brand";

void SplashScreen.preventAutoHideAsync();

/** Root of the Beavent Business app (owners, staff and organizers). Theme comes from @beavent/ui tokens. */
export default function RootLayout() {
  const { scheme, colors } = useBrand();
  useEffect(() => {
    void SplashScreen.hideAsync();
  }, []);
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
          headerShown: false,
          contentStyle: { backgroundColor: colors.background },
        }}
      />
    </ThemeProvider>
  );
}
