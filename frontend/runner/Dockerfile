# syntax=docker/dockerfile:1
FROM node:24.16.0-alpine
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --omit=dev --no-fund --no-audit
COPY runner ./runner
ENV NODE_OPTIONS=--max-old-space-size=192
USER node
ENTRYPOINT ["node", "runner/cli.mjs"]
CMD ["runner/exemplos/fundacao.xlsx", "--config", "runner/exemplos/compose.json"]
