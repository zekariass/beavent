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
  // Every admin page is per-user and rendered at request time (no prerendering or cached components).
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
