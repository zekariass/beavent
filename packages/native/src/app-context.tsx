import { createApiClient, type ApiClient } from "@beavent/api-client";
import { defaultDisplayPreferences, type DisplayPreferences } from "@beavent/calendar";
import {
  createTranslator,
  isLanguage,
  negotiateLanguage,
  type Language,
  type Translate,
} from "@beavent/i18n";
import { color, type ColorScheme, type Palette } from "@beavent/ui";
import * as SecureStore from "expo-secure-store";
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { Platform } from "react-native";

import { useColorScheme } from "./hooks/use-color-scheme";

/** Default API address (an app can pass `apiUrl` to <AppProvider>); `EXPO_PUBLIC_API_URL` is set per build. */
const DEFAULT_API_URL = process.env.EXPO_PUBLIC_API_URL ?? "http://localhost:8080";

/** The client app the API records for sign-ins (identity `ClientApp`). */
export type ClientApp = "MOBILE_APP" | "WEB_MARKETPLACE" | "PARTNER_PORTAL" | "ADMIN_CONSOLE";
const ACCESS = "beavent.access";
const REFRESH = "beavent.refresh";
const PREFERENCES = "beavent.preferences";

/** Tokens live in the platform keystore (Keychain / Keystore); the web build keeps them for the tab only. */
const store = {
  get: (key: string) =>
    Platform.OS === "web"
      ? Promise.resolve(globalThis.sessionStorage?.getItem(key) ?? null)
      : SecureStore.getItemAsync(key),
  set: (key: string, value: string) =>
    Platform.OS === "web"
      ? Promise.resolve(globalThis.sessionStorage?.setItem(key, value))
      : SecureStore.setItemAsync(key, value),
  remove: (key: string) =>
    Platform.OS === "web"
      ? Promise.resolve(globalThis.sessionStorage?.removeItem(key))
      : SecureStore.deleteItemAsync(key),
};

function secondsLeft(jwt: string | null): number {
  if (!jwt) return 0;
  try {
    const payload = JSON.parse(
      atob((jwt.split(".")[1] ?? "").replace(/-/g, "+").replace(/_/g, "/")),
    ) as { exp?: number };
    return (payload.exp ?? 0) - Math.floor(Date.now() / 1000);
  } catch {
    return 0;
  }
}

/**
 * Tokens and their refresh. One refresh at a time: two requests must never spend the same refresh token (the API
 * treats reuse as theft and ends the session).
 */
class Session {
  private access: string | null = null;
  private refresh: string | null = null;
  private refreshing: Promise<string | null> | null = null;

  constructor(
    private readonly apiUrl: string,
    private readonly onChange: (signedIn: boolean) => void,
  ) {}

  async load(): Promise<void> {
    const [access, refresh] = await Promise.all([store.get(ACCESS), store.get(REFRESH)]);
    this.access = access;
    this.refresh = refresh;
    this.onChange(refresh !== null);
  }

  async save(access: string | null, refresh: string | null): Promise<void> {
    this.access = access;
    this.refresh = refresh;
    await Promise.all([
      access ? store.set(ACCESS, access) : store.remove(ACCESS),
      refresh ? store.set(REFRESH, refresh) : store.remove(REFRESH),
    ]);
    this.onChange(refresh !== null);
  }

  accessToken = async (): Promise<string | null> => {
    const { access, refresh } = this;
    if (secondsLeft(access) > 60 || !refresh) return access;
    this.refreshing ??= fetch(`${this.apiUrl}/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: refresh }),
    })
      .then(async (response) => {
        if (!response.ok) {
          await this.save(null, null);
          return null;
        }
        const next = (await response.json()) as { accessToken: string; refreshToken: string };
        await this.save(next.accessToken, next.refreshToken);
        return next.accessToken;
      })
      .catch(() => access)
      .finally(() => {
        this.refreshing = null;
      });
    return this.refreshing;
  };
}

export type Appearance = "system" | "light" | "dark";
type CalendarChoice = DisplayPreferences["calendar"];
type TimeChoice = DisplayPreferences["timeConvention"];

/** What the person chose; unset fields follow the defaults for their language (DD7). */
interface StoredPreferences {
  language?: Language;
  calendar?: CalendarChoice;
  timeConvention?: TimeChoice;
  appearance?: Appearance;
}

/**
 * Display preferences survive restarts: the keystore on phones (expo-secure-store, already used for the session),
 * localStorage on the web build. Failures only lose the preference, never the session.
 */
const preferenceStore = {
  async load(): Promise<StoredPreferences> {
    try {
      const raw =
        Platform.OS === "web"
          ? (globalThis.localStorage?.getItem(PREFERENCES) ?? null)
          : await SecureStore.getItemAsync(PREFERENCES);
      const value = raw ? (JSON.parse(raw) as StoredPreferences) : {};
      return isLanguage(value.language) || value.language === undefined ? value : {};
    } catch {
      return {};
    }
  },
  async save(value: StoredPreferences): Promise<void> {
    try {
      const raw = JSON.stringify(value);
      if (Platform.OS === "web") globalThis.localStorage?.setItem(PREFERENCES, raw);
      else await SecureStore.setItemAsync(PREFERENCES, raw);
    } catch {
      // Not fatal: the choice still applies for this run.
    }
  },
};

interface AppState {
  /** Client app this build signs in as (sent with one-time-code requests). */
  clientApp: ClientApp;
  /** False until the stored session and preferences are loaded (the splash screen stays up). */
  ready: boolean;
  language: Language;
  setLanguage: (language: Language) => void;
  t: Translate;
  /** Calendar, time convention, language and zone for @beavent/calendar formatters. */
  display: DisplayPreferences;
  setCalendar: (calendar: CalendarChoice) => void;
  setTimeConvention: (convention: TimeChoice) => void;
  appearance: Appearance;
  setAppearance: (appearance: Appearance) => void;
  /** Resolved colour scheme (system or the person's choice) and its colour tokens. */
  scheme: ColorScheme;
  colors: Palette;
  /** null while loading the stored session */
  signedIn: boolean | null;
  api: ApiClient;
  signIn: (accessToken: string, refreshToken: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const AppContext = createContext<AppState | null>(null);

function deviceLanguage(): Language {
  try {
    return negotiateLanguage([Intl.DateTimeFormat().resolvedOptions().locale]);
  } catch {
    return "am";
  }
}

export function AppProvider({
  children,
  apiUrl = DEFAULT_API_URL,
  clientApp = "MOBILE_APP",
}: {
  children: ReactNode;
  apiUrl?: string;
  clientApp?: ClientApp;
}) {
  const [stored, setStored] = useState<StoredPreferences>({});
  const [preferencesLoaded, setPreferencesLoaded] = useState(false);
  const [signedIn, setSignedIn] = useState<boolean | null>(null);
  const [session] = useState(() => new Session(apiUrl, setSignedIn));
  const system = useColorScheme();

  useEffect(() => {
    void session.load();
    void preferenceStore.load().then((value) => {
      setStored(value);
      setPreferencesLoaded(true);
    });
  }, [session]);

  const language = stored.language ?? deviceLanguage();
  const update = (change: StoredPreferences) =>
    setStored((current) => {
      const next = { ...current, ...change };
      void preferenceStore.save(next);
      return next;
    });
  const appearance = stored.appearance ?? "system";
  const scheme: ColorScheme =
    appearance === "system" ? (system === "dark" ? "dark" : "light") : appearance;
  const defaults = defaultDisplayPreferences(language);
  const display: DisplayPreferences = {
    ...defaults,
    calendar: stored.calendar ?? defaults.calendar,
    timeConvention: stored.timeConvention ?? defaults.timeConvention,
  };

  const api = useMemo(
    () =>
      createApiClient({
        baseUrl: apiUrl,
        getAccessToken: session.accessToken,
        getLanguage: () => language,
      }),
    [apiUrl, session, language],
  );

  const value = useMemo<AppState>(
    () => ({
      ready: preferencesLoaded && signedIn !== null,
      clientApp,
      language,
      setLanguage: (next) => update({ language: next }),
      t: createTranslator(language),
      display,
      setCalendar: (calendar) => update({ calendar }),
      setTimeConvention: (timeConvention) => update({ timeConvention }),
      appearance,
      setAppearance: (next) => update({ appearance: next }),
      scheme,
      colors: color[scheme],
      signedIn,
      api,
      signIn: (access, refresh) => session.save(access, refresh),
      signOut: async () => {
        if (await session.accessToken()) {
          await api
            .POST("/v1/auth/logout", { body: { allSessions: false } })
            .catch(() => undefined);
        }
        await session.save(null, null);
      },
    }),
    // `update` and `display` are derived from the listed state each render (React Compiler memoises them).
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [language, signedIn, api, session, preferencesLoaded, stored, scheme, appearance, clientApp],
  );
  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppState {
  const app = useContext(AppContext);
  if (!app) throw new Error("useApp outside AppProvider");
  return app;
}
