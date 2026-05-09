import { NextRequest, NextResponse } from "next/server"
import { backendFetch } from "@/lib/backend"

export async function GET(request: NextRequest, ctx: { params: Promise<{ slideId: string }> }) {
  const { slideId } = await ctx.params
  const token = request.cookies.get("token")?.value
  if (!token) return NextResponse.json({ error: "Unauthorized" }, { status: 401 })
  const res = await backendFetch(`/api/iiif/${slideId}/info.json`, { token })
  const text = await res.text()
  return new NextResponse(text, {
    status: res.status,
    headers: { "Content-Type": "application/json" },
  })
}
