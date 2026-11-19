# Image for one Next.js app (web, business or admin-console). Build from the repository root:
#   docker build -f docker/next-app.Dockerfile --build-arg APP=web -t beavent-web .
ARG NODE_IMAGE=node:26-alpine

FROM ${NODE_IMAGE} AS prune
WORKDIR /repo
RUN npm install -g turbo@2.11.13
COPY . .
ARG APP
RUN turbo prune @beavent/${APP} --docker

FROM ${NODE_IMAGE} AS build
WORKDIR /repo
COPY --from=prune /repo/out/json/ .
RUN npm ci --no-fund --no-audit
COPY --from=prune /repo/out/full/ .
ARG APP
RUN npx turbo run build --filter=@beavent/${APP}

FROM ${NODE_IMAGE} AS run
ARG APP
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0 APP=${APP}
WORKDIR /app
COPY --from=build --chown=node:node /repo/${APP}/.next/standalone ./
COPY --from=build --chown=node:node /repo/${APP}/.next/static ./${APP}/.next/static
COPY --from=build --chown=node:node /repo/${APP}/public ./${APP}/public
USER node
EXPOSE 3000
CMD ["sh", "-c", "node ${APP}/server.js"]
