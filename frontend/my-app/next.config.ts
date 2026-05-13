import type { NextConfig } from "next";

// ВАЖНО: rewrites убраны намеренно.
// Раньше тут был catch-all '/api/:path*' → 'http://localhost:8080/api/:path*',
// который проксировал запросы напрямую на бек, минуя cookie → Bearer конвертацию
// в наших route handlers (app/api/.../route.ts). Из-за этого для части эндпоинтов
// бек получал запрос без Authorization и отвечал 403.
//
// Теперь все /api/* запросы идут только через route handlers, которые читают
// HTTP-only cookie 'token' и подставляют 'Authorization: Bearer ...'.

const nextConfig: NextConfig = {
  // standalone убран — на Next.js 16 standalone + Turbopack-build ломается:
  // server.js стартует, но пустой reply на все запросы. Используем обычный next start
  // (см. Dockerfile + package.json scripts.start).

  // Разрешаем запросы с ngrok-туннелей (в dev-режиме Next.js по умолчанию блокирует не-localhost)
  allowedDevOrigins: [
    "*.ngrok-free.app",
    "*.ngrok.io",
    "*.ngrok.app",
  ],
};

export default nextConfig;
