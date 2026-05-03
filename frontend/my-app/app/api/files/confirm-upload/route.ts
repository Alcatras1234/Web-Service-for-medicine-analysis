// app/api/files/confirm-upload/route.ts
import { NextRequest, NextResponse } from "next/server"
import { backendFetch } from "@/lib/backend"

export async function POST(request: NextRequest) {
  const token = request.cookies.get("token")?.value
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 })
  }

  const body = await request.text()

  try {
    const res = await backendFetch("/api/files/confirm-upload", {
      method: "POST",
      token,
      body,
      headers: { "Content-Type": "application/json" },
    })
    const text = await res.text()
    return new NextResponse(text, {
      status: res.status,
      headers: { "Content-Type": "application/json" },
    })
  } catch (e) {
    if (e instanceof Response) return e
    return NextResponse.json({ error: "Backend unavailable" }, { status: 503 })
  }
}