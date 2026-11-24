import path from "node:path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Self-contained server for the container image (docker/next-app.Dockerfile).
  output: "standalone",
  outputFileTracingRoot: path.join(__dirname, "../.."),
  // Shared workspace packages ship TypeScript source.
  transpilePackages: [
    "@beavent/api-client",
    "@beavent/i18n",
    "@beavent/calendar",
    "@beavent/money",
    "@beavent/ui",
    "@beavent/venue",
  ],
  // Not using cacheComponents / partialPrefetching: the business and admin consoles are per-user and rendered at
  // request time, and the public pages are prerendered per language (generateStaticParams). Revisit when the
  // marketplace pages need component-level caching (they would then need Suspense/loading boundaries throughout).
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
