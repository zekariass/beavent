import { NotoSansEthiopic_400Regular } from "@expo-google-fonts/noto-sans-ethiopic/400Regular";
import { NotoSansEthiopic_500Medium } from "@expo-google-fonts/noto-sans-ethiopic/500Medium";
import { NotoSansEthiopic_600SemiBold } from "@expo-google-fonts/noto-sans-ethiopic/600SemiBold";

/**
 * Noto Sans Ethiopic, bundled on every platform (decision Q4: consistent Ge'ez glyphs; iOS would otherwise fall back
 * to Kefa). Only the three weights of the type scale are imported — the package's main entry would bundle all nine.
 * Latin text keeps the system font (DD11).
 */
export const ETHIOPIC_FONT_FILES = {
  NotoSansEthiopic_400Regular,
  NotoSansEthiopic_500Medium,
  NotoSansEthiopic_600SemiBold,
};

/** Font family per weight (React Native needs one family name per weight for custom fonts). */
export const ETHIOPIC_FAMILY: Record<"400" | "500" | "600", keyof typeof ETHIOPIC_FONT_FILES> = {
  "400": "NotoSansEthiopic_400Regular",
  "500": "NotoSansEthiopic_500Medium",
  "600": "NotoSansEthiopic_600SemiBold",
};
