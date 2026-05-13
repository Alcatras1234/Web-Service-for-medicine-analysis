// app/api/files/proxy-upload/route.ts
// Стрим-прокси для PUT-аплоада крупных WSI: браузер PUT'ит сюда → форвардим тело
// без буферизации в Spring. Без этого Next.js по умолчанию пытается прочитать body
// целиком и упирается в лимиты.
import { NextRequest, NextResponse } from "next/server"
import { BACKEND_URL } from "@/lib/backend"

// Отключаем кеш + просим длинный таймаут (по умолчанию 30s — мало для GB-файлов).
export const dynamic = "force-dynamic"
export const maxDuration = 1800 // 30 минут — серверным route handlers это нужно явно

export async function PUT(request: NextRequest) {
  const token = request.cookies.get("token")?.value
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 })
  }

  const objectKey = request.nextUrl.searchParams.get("objectKey")
  if (!objectKey) {
    return NextResponse.json({ error: "objectKey required" }, { status: 400 })
  }

  const contentLength = request.headers.get("content-length") ?? ""
  const url = `${BACKEND_URL}/api/files/proxy-upload?objectKey=${encodeURIComponent(objectKey)}`

  try {
    const res = await fetch(url, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/octet-stream",
        ...(contentLength ? { "Content-Length": contentLength } : {}),
      },
      body: request.body,
      // Node fetch требует это для стриминговых тел запроса
      // @ts-expect-error duplex отсутствует в типах node-fetch / undici в TS
      duplex: "half",
      cache: "no-store",
    })
    const text = await res.text()
    return new NextResponse(text, {
      status: res.status,
      headers: { "Content-Type": "application/json" },
    })
  } catch (err) {
    console.error("proxy-upload forward failed:", err)
    return NextResponse.json(
      { error: "Backend unavailable", detail: String(err) },
      { status: 503 },
    )
  }
}
