import type { NextConfig } from "next";

// ВАЖНО: rewrites убраны намеренно.
// Раньше тут был catch-all '/api/:path*' → 'http://localhost:8080/api/:path*',
// который проксировал запросы напрямую на бек, минуя cookie → Bearer конвертацию
// в наших route handlers (app/api/.../route.ts). Из-за этого для части эндпоинтов
// бек получал запрос без Authorization и отвечал 403.
//
// Теперь все /api/* запросы идут только через route handlers, которые читают
// HTTP-only cookie 'token' и подставляют 'Authorization: Bearer ...'.
//
// Если для нового эндпоинта на беке нужен прокси — заведи его в app/api/.../route.ts
// по образцу app/api/files/slides/route.ts (использует backendFetch из @/lib/backend).

const nextConfig: NextConfig = {
  // Разрешаем запросы с ngrok-туннелей (в dev-режиме Next.js по умолчанию блокирует не-localhost)
  allowedDevOrigins: [
    "*.ngrok-free.app",
    "*.ngrok.io",
    "*.ngrok.app",
  ],
};

export default nextConfig;
