# syntax=docker/dockerfile:1.4
# Multi-stage build: builder ставит зависимости и собирает, runner поднимает standalone сервер.
# Нужен output:'standalone' в next.config.ts.

# ─── builder ──────────────────────────────────────────────────────────────────
FROM node:20-alpine AS builder

WORKDIR /app

# Кеш зависимостей выживает между билдами благодаря BuildKit cache mount
COPY package.json package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY . .

# Сборка standalone выхода: создаст .next/standalone + .next/static
RUN npm run build

# ─── runner ───────────────────────────────────────────────────────────────────
FROM node:20-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# Standalone-сборка: node-сервер + минимальный node_modules в .next/standalone
COPY --from=builder /app/.next/standalone ./
# Статика и public копируются отдельно — standalone их не включает
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

EXPOSE 3000

CMD ["node", "server.js"]
