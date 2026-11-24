import * as Haptics from "expo-haptics";
import { Platform } from "react-native";

/** Light tick when a choice is made (MOBILE_UI_GUIDELINES.md §10). No-op on the web build. */
export function selectionFeedback(): void {
  if (Platform.OS !== "web") void Haptics.selectionAsync().catch(() => undefined);
}
