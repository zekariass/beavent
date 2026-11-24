import * as WebBrowser from "expo-web-browser";

/** Address of the web platform (public site, business console). Set `EXPO_PUBLIC_WEB_URL` per build. */
const WEB_URL = process.env.EXPO_PUBLIC_WEB_URL ?? "http://localhost:3000";

/**
 * Opens a page of the web platform in the in-app browser, e.g. `openWeb("/business")`. Paths without a language
 * prefix are fine: the web proxy adds the visitor's remembered or browser language.
 */
export async function openWeb(path: string): Promise<void> {
  await WebBrowser.openBrowserAsync(`${WEB_URL}${path}`);
}
