export const SUPPORTED_LANGUAGES = ["am", "en"] as const;
export type Language = (typeof SUPPORTED_LANGUAGES)[number];
