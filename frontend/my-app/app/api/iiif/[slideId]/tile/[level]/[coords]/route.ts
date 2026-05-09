import { NextRequest, NextResponse } from "next/server"
import { backendFetch } from "@/lib/backend"

export async function GET(
  request: NextRequest,
  ctx: { params: Promise<{ slideId: string; level: string; coords: string }> }
) {
  const { slideId, level, coords } = await ctx.params
  const token = request.cookies.get("token")?.value
  if (!token) return NextResponse.json({ error: "Unauthorized" }, { status: 401 })

  const res = await backendFetch(
    `/api/iiif/${slideId}/tile/${level}/${coords}`,
    { token }
  )
  if (!res.ok) {
    return new NextResponse(null, { status: res.status })
  }
  const buf = await res.arrayBuffer()
  return new NextResponse(buf, {
    status: 200,
    headers: {
      "Content-Type": "image/jpeg",
      "Cache-Control": "public, max-age=604800",
    },
  })
}
