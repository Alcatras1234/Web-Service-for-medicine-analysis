// app/api/reports/[jobId]/status/route.ts
import { NextRequest, NextResponse } from "next/server"
import { backendFetch } from "@/lib/backend"

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ jobId: string }> }
) {
  const { jobId } = await params
  const token = request.cookies.get("token")?.value
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 })
  }

  try {
    const res = await backendFetch(`/api/reports/${jobId}/status`, { token })
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