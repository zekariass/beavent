// Design tokens shared by web (CSS variables) and React Native (plain numbers). Provisional until the visual design
// (plan dependency D9): names are stable, values will change.

export const color = {
  light: {
    background: "#ffffff",
    surface: "#f6f6f4",
    text: "#1c1b1a",
    textMuted: "#5f5d58",
    border: "#e2e0db",
    primary: "#5b3fd9",
    onPrimary: "#ffffff",
    success: "#1f7a4d",
    warning: "#a86200",
    danger: "#b42318",
  },
  dark: {
    background: "#121211",
    surface: "#1d1c1b",
    text: "#f2f1ee",
    textMuted: "#a8a5a0",
    border: "#33312e",
    primary: "#9d8cff",
    onPrimary: "#14102b",
    success: "#5cc794",
    warning: "#f0a64a",
    danger: "#f97066",
  },
} as const;

export type ColorScheme = keyof typeof color;
export type ColorToken = keyof (typeof color)["light"];

/** 4-point spacing scale (px / density-independent pixels). */
export const space = {
  0: 0,
  1: 4,
  2: 8,
  3: 12,
  4: 16,
  5: 20,
  6: 24,
  8: 32,
  10: 40,
  12: 48,
  16: 64,
} as const;

export const radius = { sm: 4, md: 8, lg: 12, xl: 16, full: 9999 } as const;

/** Type scale in px; minimum body size 16 keeps Ethiopic glyphs legible on low-density screens. */
export const fontSize = { xs: 12, sm: 14, md: 16, lg: 18, xl: 22, "2xl": 28, "3xl": 34 } as const;
export type FontSize = keyof typeof fontSize;

/**
 * Ge'ez (Ethiopic) characters are taller and denser than Latin ones: they need a larger line height and should not
 * be set below 14 px. Fonts: Noto Sans Ethiopic on the web and Android (bundled with Android); iOS falls back to
 * Kefa.
 */
export const typography = {
  Latn: {
    fontFamily: '"Noto Sans", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    lineHeight: 1.45,
    minSize: 12,
  },
  Ethi: {
    fontFamily: '"Noto Sans Ethiopic", Kefa, "Abyssinica SIL", "Nyala", system-ui, sans-serif',
    lineHeight: 1.7,
    minSize: 14,
  },
} as const;
export type Script = keyof typeof typography;

/** Line height in px for a size and script (React Native needs absolute values). */
export const lineHeightPx = (size: FontSize, script: Script): number =>
  Math.round(Math.max(fontSize[size], typography[script].minSize) * typography[script].lineHeight);

/** CSS custom properties for a colour scheme, e.g. `--bv-color-primary: #5b3fd9;`. */
export function cssVariables(scheme: ColorScheme): string {
  const colors = Object.entries(color[scheme]).map(
    ([name, value]) => `--bv-color-${kebab(name)}: ${value};`,
  );
  const spaces = Object.entries(space).map(([name, value]) => `--bv-space-${name}: ${value}px;`);
  const radii = Object.entries(radius).map(([name, value]) => `--bv-radius-${name}: ${value}px;`);
  return [...colors, ...spaces, ...radii].join("\n");
}

const kebab = (name: string) => name.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);

/**
 * Performance budget for low-end Android phones (2 GB RAM, Android 9+, slow 3G); checked in CI as features land.
 */
export const performanceBudget = {
  /** Mobile JavaScript bundle (Hermes bytecode), compressed. */
  mobileBundleKb: 4_000,
  /** Cold start to first interactive screen on the reference device. */
  mobileColdStartMs: 3_000,
  /** Web: first-load JavaScript per route, compressed. */
  webFirstLoadJsKb: 200,
  /** Web: Largest Contentful Paint on a slow 3G profile. */
  webLcpMs: 4_000,
  /** Images in lists use the 320 px variant; full size only on detail screens. */
  listImageWidth: 320,
  /** Lists longer than this are virtualized. */
  maxUnvirtualizedRows: 50,
} as const;
