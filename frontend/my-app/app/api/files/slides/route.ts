// app/api/files/slides/route.ts
import { NextRequest, NextResponse } from "next/server"
import { backendFetch } from "@/lib/backend"

export async function GET(request: NextRequest) {
  const token = request.cookies.get("token")?.value
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 })
  }

  try {
    const res = await backendFetch("/api/files/slides", { token })
    const text = await res.text()
    if (!res.ok) {
      return NextResponse.json(
        { error: "Backend error", status: res.status, body: text },
        { status: res.status }
      )
    }
    return new NextResponse(text, {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })
  } catch (e) {
    if (e instanceof Response) return e
    return NextResponse.json(
      { error: "Backend unavailable" },
      { status: 503 }
    )
  }
}