import { NextRequest, NextResponse } from "next/server"
import { backendFetch } from "@/lib/backend"

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ jobId: string }> }
) {
  const { jobId } = await params
  const token = request.cookies.get("token")?.value
  if (!token) return NextResponse.json({ error: "Unauthorized" }, { status: 401 })

  const res = await backendFetch(`/api/reports/${jobId}/heatmap`, { token })
  if (!res.ok) {
    return NextResponse.json({ error: "Heatmap not ready" }, { status: res.status })
  }
  const buf = await res.arrayBuffer()
  return new NextResponse(buf, {
    status: 200,
    headers: {
      "Content-Type": "image/png",
      "Content-Disposition": `inline; filename="heatmap-${jobId}.png"`,
    },
  })
}
