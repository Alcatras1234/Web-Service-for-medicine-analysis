// frontend/my-app/lib/backend.ts
// Единая точка для обращения к Spring Boot бэкенду из Next.js API routes.
// На локальной разработке Next.js обычно сам бежит на хосте и видит бэкенд
// по http://localhost:8080. В docker-compose Next.js нужно ходить по
// http://server:8080 (имя контейнера auth/server в compose).
//
// Управляйте через .env.local:
//   BACKEND_URL=http://localhost:8080      # dev на хосте
//   BACKEND_URL=http://server:8080         # внутри docker-compose
//
// IMPORTANT: НЕ кладите NEXT_PUBLIC_ — этот URL должен быть только серверным.

export const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080"

export type BackendFetchInit = RequestInit & { token?: string }

export async function backendFetch(
  path: string,
  init: BackendFetchInit = {}
): Promise<Response> {
  const headers = new Headers(init.headers)
  if (init.token) {
    headers.set("Authorization", `Bearer ${init.token}`)
  }
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json")
  }

  // Никаких кук между Next.js server → Spring; идёт чистый HTTP с Bearer.
  const url = `${BACKEND_URL}${path.startsWith("/") ? path : "/" + path}`

  try {
    return await fetch(url, { ...init, headers, cache: "no-store" })
  } catch (err) {
    // Прозрачная ошибка вместо стека Next.js
    console.error(`backendFetch failed: ${url}`, err)
    throw new Response(
      JSON.stringify({ error: "Backend unavailable", detail: String(err) }),
      { status: 503, headers: { "Content-Type": "application/json" } }
    )
  }
}