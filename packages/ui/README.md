# @beavent/ui

Design tokens for web and React Native: colours (light/dark), spacing, radii, type scale, and typography rules per
script. Values are provisional until the visual design is ready (plan dependency D9); token names are stable.

- **Ethiopic text** (Amharic, Tigrinya): line height 1.7 (Latin 1.45), never below 14 px. Web loads Noto Sans
  Ethiopic; Android ships it; iOS falls back to Kefa. Use `scriptOf(language)` from `@beavent/i18n` to choose.
- **Web**: `cssVariables("light")` / `cssVariables("dark")` produce `--bv-*` custom properties for the apps' CSS.
- **React Native**: use the numbers directly (`space[4]`, `lineHeightPx("md", "Ethi")`).
- **Low-end Android budget** (`performanceBudget`): ≤ 4 MB mobile bundle, ≤ 3 s cold start on a 2 GB device,
  ≤ 200 KB first-load JS per web route, LCP ≤ 4 s on slow 3G, 320 px image variants in lists, virtualized long lists.

Components follow the screens' designs (D9); shared ones are added here as they appear in two apps.
