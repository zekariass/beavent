// Beavent design tokens — the single source of truth for every app (docs/design/beavent/DESIGN_TOKENS.md).
// Web consumes them through the generated `tokens.css` (CSS variables + Tailwind theme + type-role classes);
// React Native consumes the values below directly. Token names are stable; change values here, then run
// `npm run generate -w @beavent/ui` (a test fails if tokens.css is stale).

// --- Colour -------------------------------------------------------------------------------------------------------

/** Semantic colours ("Warm canvas, emerald ink, Adey gold"). Screens use these names, never hex values. */
export const color = {
  light: {
    background: "#ffffff",
    surface: "#f5f4f1",
    surfaceRaised: "#ffffff",
    surfacePressed: "#ebe9e4",
    text: "#1a1917",
    textMuted: "#5e5b55",
    textSubtle: "#8f8b83",
    textInverse: "#ffffff",
    border: "#e4e1da",
    borderStrong: "#8f8b83",
    primary: "#0b6b53",
    primaryPressed: "#085542",
    primarySubtle: "#e2f0ea",
    onPrimary: "#ffffff",
    accent: "#f0b323",
    onAccent: "#1a1917",
    success: "#1f7a4d",
    successSubtle: "#e5f3eb",
    warning: "#a86200",
    warningSubtle: "#fcf0dc",
    danger: "#b42318",
    dangerSubtle: "#fce9e7",
    onDanger: "#ffffff",
    info: "#1f5bc4",
    infoSubtle: "#e6eefb",
    scrim: "rgba(17, 16, 15, 0.48)",
    imageScrim: "rgba(0, 0, 0, 0.40)",
    focus: "#0b6b53",
  },
  dark: {
    background: "#121211",
    surface: "#1d1c1b",
    surfaceRaised: "#252422",
    surfacePressed: "#2e2d2a",
    text: "#f2f1ee",
    textMuted: "#a8a5a0",
    textSubtle: "#74716b",
    textInverse: "#121211",
    border: "#33312e",
    borderStrong: "#6b6862",
    primary: "#5cc8a1",
    primaryPressed: "#7ad6b5",
    primarySubtle: "#133a2f",
    onPrimary: "#06261d",
    accent: "#f5c542",
    onAccent: "#1a1917",
    success: "#5cc794",
    successSubtle: "#15301f",
    warning: "#f0a64a",
    warningSubtle: "#33240f",
    danger: "#f97066",
    dangerSubtle: "#3a1714",
    onDanger: "#1a1917",
    info: "#7ea6f2",
    infoSubtle: "#172440",
    scrim: "rgba(0, 0, 0, 0.60)",
    imageScrim: "rgba(0, 0, 0, 0.40)",
    focus: "#5cc8a1",
  },
} as const;

export type ColorScheme = keyof typeof color;
export type ColorToken = keyof (typeof color)["light"];
export type Palette = { readonly [K in ColorToken]: string };

/**
 * Foreground/background pairs that screens may use, with the minimum WCAG 2.2 contrast each must reach
 * (4.5 text, 3 large text and UI component boundaries). Tested in both schemes.
 */
export const contrastPairs: readonly { fg: ColorToken; bg: ColorToken; min: number }[] = [
  { fg: "text", bg: "background", min: 4.5 },
  { fg: "text", bg: "surface", min: 4.5 },
  { fg: "text", bg: "surfaceRaised", min: 4.5 },
  { fg: "textMuted", bg: "background", min: 4.5 },
  { fg: "textMuted", bg: "surface", min: 4.5 },
  { fg: "primary", bg: "background", min: 4.5 },
  { fg: "primary", bg: "primarySubtle", min: 4.5 },
  { fg: "onPrimary", bg: "primary", min: 4.5 },
  { fg: "onPrimary", bg: "primaryPressed", min: 4.5 },
  { fg: "onAccent", bg: "accent", min: 4.5 },
  { fg: "onDanger", bg: "danger", min: 4.5 },
  { fg: "textInverse", bg: "text", min: 4.5 },
  { fg: "success", bg: "background", min: 4.5 },
  { fg: "warning", bg: "background", min: 4.5 },
  { fg: "danger", bg: "background", min: 4.5 },
  { fg: "info", bg: "background", min: 4.5 },
  // Banners: body text is `text`; the status colour on its subtle background is for the icon only (graphics, 3:1).
  { fg: "text", bg: "successSubtle", min: 4.5 },
  { fg: "text", bg: "warningSubtle", min: 4.5 },
  { fg: "text", bg: "dangerSubtle", min: 4.5 },
  { fg: "text", bg: "infoSubtle", min: 4.5 },
  { fg: "success", bg: "successSubtle", min: 3 },
  { fg: "warning", bg: "warningSubtle", min: 3 },
  { fg: "danger", bg: "dangerSubtle", min: 3 },
  { fg: "info", bg: "infoSubtle", min: 3 },
  { fg: "borderStrong", bg: "background", min: 3 },
  { fg: "focus", bg: "background", min: 3 },
];

const channel = (value: number) => {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};

/** Relative luminance of a `#rrggbb` colour (WCAG 2.2). */
export function luminance(hex: string): number {
  const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
  if (!match) throw new RangeError(`not a #rrggbb colour: ${hex}`);
  const [r, g, b] = match.slice(1).map((part) => channel(parseInt(part, 16))) as [
    number,
    number,
    number,
  ];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG contrast ratio between two `#rrggbb` colours (1–21). */
export function contrastRatio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

// --- Spacing, layout, sizes ---------------------------------------------------------------------------------------

/** 4-point spacing scale (px / density-independent pixels). The only spacing values screens may use. */
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

/** Layout roles built from the spacing scale. */
export const layout = {
  /** Horizontal page padding on phones. */
  gutter: space[4],
  /** Horizontal page padding ≥ 600 dp. */
  gutterWide: space[6],
  /** Between sections of a scrolling screen. */
  sectionGap: space[8],
  /** Section title → its content. */
  titleGap: space[3],
  /** Between lines inside one item, and between inline items. */
  stackGap: space[2],
  inlineGap: space[2],
  /** Between cards in a horizontal rail. */
  railGap: space[3],
  /** Vertical padding of list rows (minimum row height 56). */
  rowPaddingY: space[3],
  rowMinHeight: 56,
  /** Mobile web and tablets. */
  maxContentWidth: 800,
  /** Text-heavy columns on tablets and web. */
  readingWidth: 640,
  /** Web marketplace grids. */
  webContainer: 1200,
} as const;

/** Breakpoints for web and tablets (min widths). */
export const breakpoints = { medium: 600, expanded: 1024 } as const;

/** Component sizes (dp/px). */
export const size = {
  touchTarget: 48,
  button: { lg: 52, md: 44, sm: 36 },
  input: 52,
  chip: 36,
  slot: 48,
  icon: { sm: 16, md: 20, lg: 24 },
  avatar: { xs: 24, sm: 32, md: 40, lg: 56, xl: 80 },
} as const;

export const radius = { sm: 4, md: 8, lg: 12, xl: 16, full: 9999 } as const;

/** Hairline dividers use the platform hairline; these are the other border widths. */
export const borderWidth = { input: 1, selected: 1.5, focus: 2 } as const;

// --- Typography ---------------------------------------------------------------------------------------------------

/** Type scale in px. 3xl is reserved for web marketing headings; the apps top out at 2xl (28). */
export const fontSize = { xs: 12, sm: 14, md: 16, lg: 18, xl: 22, "2xl": 28, "3xl": 34 } as const;
export type FontSize = keyof typeof fontSize;

/**
 * Ge'ez (Ethiopic) characters are taller and denser than Latin ones: they need a larger line height and should not
 * be set below 14 px. Fonts: Noto Sans Ethiopic on the web and Android (bundled with Android); iOS falls back to
 * Kefa (bundling Noto Sans Ethiopic on iOS is open question Q4). Latin uses the system font on mobile and Noto Sans
 * on the web.
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

/** Weights: 400, 500, 600 only (no thin weights; Ge'ez strokes break up on low-density screens). */
export const fontWeight = { regular: "400", medium: "500", semibold: "600" } as const;

/**
 * Type roles. Line heights are absolute px per script; Ge'ez headings use 1.5, Ge'ez body text 1.7, and Ge'ez text
 * is never smaller than 14 px (the caption is raised).
 */
export const textRoles = {
  display: { size: "2xl", weight: "semibold", lineHeight: { Latn: 34, Ethi: 42 } },
  title1: { size: "xl", weight: "semibold", lineHeight: { Latn: 28, Ethi: 34 } },
  title2: { size: "lg", weight: "semibold", lineHeight: { Latn: 24, Ethi: 28 } },
  body: { size: "md", weight: "regular", lineHeight: { Latn: 23, Ethi: 27 } },
  bodyStrong: { size: "md", weight: "semibold", lineHeight: { Latn: 23, Ethi: 27 } },
  label: { size: "sm", weight: "medium", lineHeight: { Latn: 20, Ethi: 24 } },
  labelStrong: { size: "sm", weight: "semibold", lineHeight: { Latn: 20, Ethi: 24 } },
  caption: { size: "xs", weight: "regular", lineHeight: { Latn: 17, Ethi: 24 } },
  buttonLarge: { size: "md", weight: "semibold", lineHeight: { Latn: 20, Ethi: 24 } },
} as const satisfies Record<
  string,
  { size: FontSize; weight: keyof typeof fontWeight; lineHeight: Record<Script, number> }
>;
export type TextRole = keyof typeof textRoles;

/** Font size in px for a size and script: Ge'ez text is raised to its 14 px minimum. */
export const fontSizePx = (size: FontSize, script: Script): number =>
  Math.max(fontSize[size], typography[script].minSize);

/** Line height in px for a size and script (React Native needs absolute values). */
export const lineHeightPx = (size: FontSize, script: Script): number =>
  Math.round(Math.max(fontSize[size], typography[script].minSize) * typography[script].lineHeight);

/** Size, line height and weight of a type role in a script. */
export function textStyle(
  role: TextRole,
  script: Script,
): {
  fontSize: number;
  lineHeight: number;
  fontWeight: (typeof fontWeight)[keyof typeof fontWeight];
} {
  const spec = textRoles[role];
  return {
    fontSize: fontSizePx(spec.size, script),
    lineHeight: spec.lineHeight[script],
    fontWeight: fontWeight[spec.weight],
  };
}

/** Script of a piece of text: Ge'ez if it contains any Ethiopic character (for names typed in another script). */
export function scriptOfText(text: string): Script {
  return /[ሀ-᎟ⶀ-⷟꬀-꬯]/.test(text) ? "Ethi" : "Latn";
}

// --- Elevation and motion -----------------------------------------------------------------------------------------

/** Elevation for floating things only (light theme). Dark mode uses surfaceRaised and no shadow. */
export const elevation = {
  sticky: {
    shadowColor: "#000000",
    shadowOffset: { width: 0, height: -1 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 4,
  },
  overlay: {
    shadowColor: "#000000",
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.16,
    shadowRadius: 24,
    elevation: 12,
  },
  toast: {
    shadowColor: "#000000",
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.2,
    shadowRadius: 16,
    elevation: 8,
  },
} as const;

export const duration = { fast: 120, base: 200, slow: 300 } as const;
/** Cubic-bezier control points. */
export const easing = { standard: [0.2, 0, 0, 1], exit: [0.3, 0, 1, 1] } as const;

// --- Web output ---------------------------------------------------------------------------------------------------

const kebab = (name: string) => name.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);

/** CSS custom properties for a colour scheme, e.g. `--bv-color-primary: #0b6b53;`. */
export function cssVariables(scheme: ColorScheme): string {
  const colors = Object.entries(color[scheme]).map(
    ([name, value]) => `--bv-color-${kebab(name)}: ${value};`,
  );
  const spaces = Object.entries(space).map(([name, value]) => `--bv-space-${name}: ${value}px;`);
  const radii = Object.entries(radius).map(([name, value]) => `--bv-radius-${name}: ${value}px;`);
  const layouts = Object.entries(layout).map(
    ([name, value]) => `--bv-layout-${kebab(name)}: ${value}px;`,
  );
  return [...colors, ...spaces, ...radii, ...layouts].join("\n");
}

const colorVariables = (scheme: ColorScheme, indent: string) =>
  Object.entries(color[scheme])
    .map(([name, value]) => `${indent}--bv-color-${kebab(name)}: ${value};`)
    .join("\n");

/**
 * The shared web stylesheet: variables for both schemes (system preference, overridable with
 * `<html data-theme="light|dark">`), the Tailwind theme mapping (`bg-surface`, `text-fg-muted`, `rounded-lg` = 12 px …)
 * and the type-role classes (`bv-type-title1` …) with Ge'ez metrics under `[data-script="Ethi"]`.
 */
export function tokenStylesheet(): string {
  const lines: string[] = [
    "/* GENERATED from packages/ui/src/index.ts by `npm run generate -w @beavent/ui`. Do not edit by hand. */",
    "",
    ":root {",
    "  color-scheme: light;",
    cssVariables("light")
      .split("\n")
      .map((l) => `  ${l}`)
      .join("\n"),
    "}",
    "",
    "@media (prefers-color-scheme: dark) {",
    '  :root:not([data-theme="light"]) {',
    "    color-scheme: dark;",
    colorVariables("dark", "    "),
    "  }",
    "}",
    "",
    ':root[data-theme="dark"] {',
    "  color-scheme: dark;",
    colorVariables("dark", "  "),
    "}",
    "",
    "/* Tailwind utilities: bg-background, bg-surface, text-fg, text-fg-muted, border-border, bg-primary, text-on-primary … */",
    "@theme inline {",
  ];
  const twName: Partial<Record<ColorToken, string>> = {
    text: "fg",
    textMuted: "fg-muted",
    textSubtle: "fg-subtle",
    textInverse: "fg-inverse",
  };
  for (const name of Object.keys(color.light) as ColorToken[]) {
    lines.push(`  --color-${twName[name] ?? kebab(name)}: var(--bv-color-${kebab(name)});`);
  }
  for (const [name, value] of Object.entries(radius)) {
    if (name !== "full") lines.push(`  --radius-${name}: ${value}px;`);
  }
  for (const [name, value] of Object.entries(fontSize)) {
    lines.push(`  --text-${name === "md" ? "base" : name}: ${value}px;`);
  }
  lines.push("}", "", "/* Type roles. Use these instead of ad-hoc font sizes. */");
  for (const [role, spec] of Object.entries(textRoles)) {
    const cls = `.bv-type-${kebab(role)}`;
    lines.push(
      `${cls} {`,
      `  font-size: ${fontSizePx(spec.size, "Latn")}px;`,
      `  line-height: ${spec.lineHeight.Latn}px;`,
      `  font-weight: ${fontWeight[spec.weight]};`,
      "}",
      `[data-script="Ethi"] ${cls},`,
      `${cls}:lang(am),`,
      `${cls}:lang(ti) {`,
      `  font-size: ${fontSizePx(spec.size, "Ethi")}px;`,
      `  line-height: ${spec.lineHeight.Ethi}px;`,
      "}",
    );
  }
  lines.push(
    "",
    "/* Tabular figures for prices, times and counts. */",
    ".bv-numeric {",
    "  font-variant-numeric: tabular-nums;",
    "}",
    "",
    "/* Native checkboxes, radios and range inputs in the brand colour. */",
    ":root {",
    "  accent-color: var(--bv-color-primary);",
    "}",
    "",
    "/* Visible keyboard focus everywhere (2 px ring, 2 px offset). */",
    ":focus-visible {",
    "  outline: 2px solid var(--bv-color-focus);",
    "  outline-offset: 2px;",
    "}",
    "",
  );
  return lines.join("\n");
}

// --- Performance --------------------------------------------------------------------------------------------------

/**
 * Performance budget for low-end Android phones (2 GB RAM, Android 9+, slow 3G); checked in CI as features land.
 */
export const performanceBudget = {
  /**
   * Mobile JavaScript bundle: gzip-compressed Hermes bytecode of the Android export, in KiB (what users download).
   * Enforced in CI by `npm run bundle:check -w @beavent/public-mobile`, which also reports the raw size and assets.
   */
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
