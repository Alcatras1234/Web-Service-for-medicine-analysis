import { NextRequest, NextResponse } from 'next/server'

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()

    const backendResponse = await fetch('http://localhost:8080/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })

    const data = await backendResponse.json()

    if (!backendResponse.ok) {
      return NextResponse.json(data, { status: backendResponse.status })
    }

    // ngrok терминирует SSL и форвардит на http://localhost:3000 — поэтому request.url
    // у нас всегда http://. Реальный протокол клиента — в заголовке X-Forwarded-Proto.
    const forwardedProto = request.headers.get('x-forwarded-proto')
    const isSecure = forwardedProto === 'https' || request.nextUrl.protocol === 'https:'

    const response = NextResponse.json(data)
    response.cookies.set('token', data.token, {
      httpOnly: false,
      path: '/',
      maxAge: 86400,
      sameSite: 'lax',
      secure: isSecure,
    })
    return response
  } catch {
    return NextResponse.json({ error: 'Backend unavailable' }, { status: 503 })
  }
}
