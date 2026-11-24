// @beavent/native — the code both Beavent mobile apps (apps/public-mobile, apps/business-mobile) share:
// token-based primitives, the app provider (session, language, calendar/time/theme preferences), the Ge'ez font,
// haptics and the sign-in panel. Each app keeps its own routes, tabs and screens.
// Specs: docs/design/beavent/COMPONENT_GUIDELINES.md. Add components here (and to the guidelines) before using new
// patterns in a screen.
export * from "./ui";
export { AppProvider, useApp, type Appearance, type ClientApp } from "./app-context";
export { SignInPanel } from "./sign-in-panel";
export { selectionFeedback } from "./haptics";
export { ETHIOPIC_FAMILY, ETHIOPIC_FONT_FILES } from "./constants/fonts";
export { TAB_BAR_INSET } from "./constants/layout";
export { useColorScheme } from "./hooks/use-color-scheme";
export { useBeaventShell } from "./shell";
export { PreferencesSections, SignedInSummary } from "./preferences";
export { openWeb } from "./links";
export { BeaventTabs } from "./tabs";
export type { BeaventTabsProps, TabSpec } from "./tabs-types";
