import path from "node:path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Self-contained server for container images (docker/next-app.Dockerfile).
  output: "standalone",
  outputFileTracingRoot: path.join(__dirname, ".."),
  // Shared workspace packages ship TypeScript source.
  transpilePackages: [
    "@beavent/api-client",
    "@beavent/i18n",
    "@beavent/calendar",
    "@beavent/money",
    "@beavent/ui",
  ],
  // The portal is per-user and rendered at request time; the public landing pages are prerendered per language.
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
