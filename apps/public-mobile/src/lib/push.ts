import type { ApiClient } from "@beavent/api-client";
import Constants from "expo-constants";
import * as SecureStore from "expo-secure-store";
import { Platform } from "react-native";

// Push notifications on this phone (plan 8.4). `expo-notifications` is loaded when needed and every failure leads to
// "unsupported" (the web build, a build made before the module was added, an app without an Expo project id), so the
// rest of the app never depends on it.

type Notifications = typeof import("expo-notifications");

const TOKEN_KEY = "beavent.push-token";

/** `unsupported`: no push here; `denied`: the phone's settings forbid it; `off`: not set up; `on`: this phone is set up. */
export type PushState = "unsupported" | "denied" | "off" | "on";

async function load(): Promise<Notifications | null> {
  if (Platform.OS === "web") return null;
  // A build made before the module was added has no native side; importing it then would raise an error.
  const modules = (globalThis as { expo?: { modules?: Record<string, unknown> } }).expo?.modules;
  if (!modules?.ExpoPushTokenManager) return null;
  try {
    return await import("expo-notifications");
  } catch {
    return null;
  }
}

const projectId = (): string | undefined =>
  (Constants.expoConfig?.extra?.eas as { projectId?: string } | undefined)?.projectId ??
  Constants.easConfig?.projectId;

async function storedToken(): Promise<string | null> {
  try {
    return await SecureStore.getItemAsync(TOKEN_KEY);
  } catch {
    return null;
  }
}

/** Where this phone stands: set up, off, forbidden by its settings, or unable to. */
export async function pushState(): Promise<PushState> {
  const N = await load();
  if (!N || !projectId()) return "unsupported";
  try {
    const permission = await N.getPermissionsAsync();
    if (permission.status === "granted") return (await storedToken()) ? "on" : "off";
    return permission.canAskAgain === false ? "denied" : "off";
  } catch {
    return "unsupported";
  }
}

async function register(N: Notifications, api: ApiClient, language: string): Promise<boolean> {
  if (Platform.OS === "android") {
    await N.setNotificationChannelAsync("default", {
      name: "Beavent",
      importance: N.AndroidImportance.DEFAULT,
    });
  }
  const token = (await N.getExpoPushTokenAsync({ projectId: projectId()! })).data;
  // The token is unique to this installation and not guessable, so it also names the installation.
  const { response } = await api.POST("/v1/me/devices", {
    body: {
      installationId: token,
      platform: Platform.OS === "ios" ? "IOS" : "ANDROID",
      pushToken: token,
      appVersion: Constants.expoConfig?.version,
      locale: language,
    },
  });
  if (!response.ok) return false;
  await SecureStore.setItemAsync(TOKEN_KEY, token);
  return true;
}

/** Asks for permission if needed and sets this phone up for the signed-in person. */
export async function enablePush(api: ApiClient, language: string): Promise<PushState> {
  const N = await load();
  if (!N || !projectId()) return "unsupported";
  try {
    let permission = await N.getPermissionsAsync();
    if (permission.status !== "granted") permission = await N.requestPermissionsAsync();
    if (permission.status !== "granted") return permission.canAskAgain === false ? "denied" : "off";
    return (await register(N, api, language)) ? "on" : "off";
  } catch {
    return "unsupported";
  }
}

/** Quietly keeps this phone's registration current for the signed-in person; never asks for anything. */
export async function syncPush(api: ApiClient, language: string): Promise<void> {
  const N = await load();
  if (!N || !projectId()) return;
  try {
    if ((await N.getPermissionsAsync()).status === "granted") await register(N, api, language);
  } catch {
    // Offline or not set up: it is tried again the next time the app opens.
  }
}

/** Stops pushes to this phone for the signed-in person (signing out, or turning them off). */
export async function disablePush(api: ApiClient): Promise<void> {
  const token = await storedToken();
  if (!token) return;
  try {
    await api.DELETE("/v1/me/devices/{installationId}", {
      params: { path: { installationId: token } },
    });
    await SecureStore.deleteItemAsync(TOKEN_KEY);
  } catch {
    // Offline: the next push that finds the phone gone forgets it.
  }
}

/**
 * Shows notifications while the app is open, and opens the screen a tapped notification names (`data.route`), also
 * when the tap started the app. Returns a function that stops listening.
 */
export async function listenForPushes(open: (route: string) => void): Promise<() => void> {
  const N = await load();
  if (!N) return () => undefined;
  try {
    N.setNotificationHandler({
      handleNotification: async () => ({
        shouldPlaySound: true,
        shouldSetBadge: false,
        shouldShowBanner: true,
        shouldShowList: true,
      }),
    });
    const routeOf = (response: import("expo-notifications").NotificationResponse | null) => {
      const route = response?.notification.request.content.data?.route;
      if (typeof route === "string" && route.startsWith("/")) open(route);
    };
    routeOf(N.getLastNotificationResponse());
    const subscription = N.addNotificationResponseReceivedListener(routeOf);
    return () => subscription.remove();
  } catch {
    return () => undefined;
  }
}
