# syntax=docker/dockerfile:1.4
# Простой подход без standalone — на Next.js 16 standalone + Turbopack пока ломается.
# Образ больше (~500MB вместо 200MB), но стабильно работает.

FROM node:20-alpine

WORKDIR /app

# Кеш зависимостей выживает между билдами благодаря BuildKit cache mount
COPY package.json package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY . .

RUN npm run build

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=27
ENV HOSTNAME=0.0.0.0

EXPOSE 27

CMD ["npm", "start"]
