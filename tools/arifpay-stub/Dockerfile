# ArifPay checkout stand-in (development and CI only). Node 26 runs the TypeScript sources directly.
FROM node:26-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
ENV NODE_ENV=production PORT=8091
EXPOSE 8091
USER node
HEALTHCHECK --interval=10s --timeout=3s CMD wget -qO- http://127.0.0.1:8091/health || exit 1
CMD ["node", "src/server.ts"]
