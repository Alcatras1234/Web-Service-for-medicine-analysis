import { NextRequest, NextResponse } from 'next/server'
import { backendFetch } from '@/lib/backend'

export async function POST(request: NextRequest) {
  const body = await request.json()

  // Используем backendFetch — он берёт URL из process.env.BACKEND_URL.
  // В docker compose это http://server:8080 (имя контейнера),
  // при локальном `npm run dev` — http://127.0.0.1:8080.
  let backendResponse: Response
  try {
    backendResponse = await backendFetch('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  } catch (err) {
    if (err instanceof Response) return err
    console.error('login: backend unavailable', err)
    return NextResponse.json(
      { error: 'Backend unavailable', detail: String(err) },
      { status: 503 },
    )
  }

  const data = await backendResponse.json().catch(() => null)
  if (!backendResponse.ok || !data?.token) {
    return NextResponse.json(
      data ?? { error: `Auth failed (${backendResponse.status})` },
      { status: backendResponse.status || 502 },
    )
  }

  // Реальный протокол клиента — в заголовке X-Forwarded-Proto (за nginx/ngrok).
  const forwardedProto = request.headers.get('x-forwarded-proto')
  const isSecure =
    forwardedProto === 'https' || request.nextUrl.protocol === 'https:'

  const response = NextResponse.json(data)
  response.cookies.set('token', data.token, {
    httpOnly: false,
    path: '/',
    maxAge: 86400,
    sameSite: 'lax',
    secure: isSecure,
  })
  return response
}
