import { Platform } from "react-native";

/**
 * Space the native tab bar takes over scrolling content (values carried over from the Expo template's tab layout;
 * re-check when the tab bar changes). Screens inside the tabs pad their scroll content with it.
 */
export const TAB_BAR_INSET = Platform.select({ ios: 50, android: 80 }) ?? 0;
