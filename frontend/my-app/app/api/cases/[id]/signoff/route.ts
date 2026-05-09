import { NextRequest, NextResponse } from "next/server"
import { backendFetch } from "@/lib/backend"

export async function POST(request: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params
  const token = request.cookies.get("token")?.value
  if (!token) return NextResponse.json({ error: "Unauthorized" }, { status: 401 })
  const body = await request.text()
  const res = await backendFetch(`/api/cases/${id}/signoff`, { token, method: "POST", body })
  const text = await res.text()
  return new NextResponse(text, {
    status: res.status,
    headers: { "Content-Type": "application/json" },
  })
}
