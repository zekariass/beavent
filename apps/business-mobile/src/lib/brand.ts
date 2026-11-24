import { negotiateLanguage, createTranslator, scriptOf } from "@beavent/i18n";
import { color, textStyle, type ColorScheme } from "@beavent/ui";

import { useSyncExternalStore } from "react";

import { useColorScheme } from "@/hooks/use-color-scheme";

const subscribe = () => () => {};
function deviceLanguage() {
  try {
    return negotiateLanguage([Intl.DateTimeFormat().resolvedOptions().locale]);
  } catch {
    return negotiateLanguage(undefined);
  }
}

/**
 * Minimal theme and language access for the app shell. The shared preferences provider, token-based components and
 * session handling are still to be shared with the consumer app (see README) — until then, screens use only
 * `@beavent/ui` tokens through this hook.
 */
export function useBrand() {
  const system = useColorScheme();
  const scheme: ColorScheme = system === "dark" ? "dark" : "light";
  // The server (web build) renders in the default language; the browser or phone switches to the device language
  // after hydration, so server and first client render always match.
  const language = useSyncExternalStore(subscribe, deviceLanguage, () =>
    negotiateLanguage(undefined),
  );
  return {
    scheme,
    colors: color[scheme],
    language,
    t: createTranslator(language),
    script: scriptOf(language),
    text: (role: Parameters<typeof textStyle>[0]) => textStyle(role, scriptOf(language)),
  };
}
