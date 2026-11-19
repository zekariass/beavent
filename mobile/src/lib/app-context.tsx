import { createApiClient, type ApiClient } from "@beavent/api-client";
import { createTranslator, negotiateLanguage, type Language, type Translate } from "@beavent/i18n";
import * as SecureStore from "expo-secure-store";
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { Platform } from "react-native";

const API_URL = process.env.EXPO_PUBLIC_API_URL ?? "http://localhost:8080";
const ACCESS = "beavent.access";
const REFRESH = "beavent.refresh";

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

  constructor(private readonly onChange: (signedIn: boolean) => void) {}

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
    this.refreshing ??= fetch(`${API_URL}/v1/auth/refresh`, {
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

interface AppState {
  language: Language;
  setLanguage: (language: Language) => void;
  t: Translate;
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

export function AppProvider({ children }: { children: ReactNode }) {
  const [language, setLanguage] = useState<Language>(deviceLanguage);
  const [signedIn, setSignedIn] = useState<boolean | null>(null);
  const [session] = useState(() => new Session(setSignedIn));

  useEffect(() => {
    void session.load();
  }, [session]);

  const api = useMemo(
    () =>
      createApiClient({
        baseUrl: API_URL,
        getAccessToken: session.accessToken,
        getLanguage: () => language,
      }),
    [session, language],
  );

  const value = useMemo<AppState>(
    () => ({
      language,
      setLanguage,
      t: createTranslator(language),
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
    [language, signedIn, api, session],
  );
  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppState {
  const app = useContext(AppContext);
  if (!app) throw new Error("useApp outside AppProvider");
  return app;
}
