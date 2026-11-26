import { AppProvider, useApp, useBeaventShell } from "@beavent/native";
import { Stack, ThemeProvider, useRouter, type Href } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { useEffect } from "react";

import { listenForPushes, syncPush } from "../lib/push";

export default function RootLayout() {
  return (
    <AppProvider>
      <Shell />
    </AppProvider>
  );
}

/** Root stack: the consumer tabs, plus the sign-in modal (DD3). */
function Shell() {
  const { t, api, language, signedIn } = useApp();
  const router = useRouter();
  const { ready, theme, scheme, colors } = useBeaventShell();
  // A tapped notification opens the screen it names; a signed-in person's phone is kept registered for pushes.
  useEffect(() => {
    let stop: () => void = () => undefined;
    let cancelled = false;
    void listenForPushes((route) => router.push(route as Href)).then((off) => {
      if (cancelled) off();
      else stop = off;
    });
    return () => {
      cancelled = true;
      stop();
    };
  }, [router]);
  useEffect(() => {
    if (signedIn) void syncPush(api, language);
  }, [api, language, signedIn]);
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
          // iOS would print the previous route's name ("(tabs)") next to the arrow.
          headerBackButtonDisplayMode: "minimal",
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="sign-in" options={{ presentation: "modal", title: t("nav.signIn") }} />
        {/* Search covers the tabs and fades in (MARKETPLACE_NAVIGATION.md §4); results and places push over them. */}
        <Stack.Screen
          name="search"
          options={{ presentation: "fullScreenModal", animation: "fade", headerShown: false }}
        />
        {/* Booking is a full-screen flow over the venue (DD4). */}
        <Stack.Screen
          name="b/[business]/[location]/book"
          options={{ presentation: "fullScreenModal" }}
        />
      </Stack>
    </ThemeProvider>
  );
}
