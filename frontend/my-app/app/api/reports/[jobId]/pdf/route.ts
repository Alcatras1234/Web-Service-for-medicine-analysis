// app/api/reports/[jobId]/pdf/route.ts
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
    const res = await backendFetch(`/api/reports/${jobId}/pdf`, { token })
    if (!res.ok) {
      const text = await res.text()
      return NextResponse.json(
        { error: "Report not ready", detail: text },
        { status: res.status }
      )
    }
    const buf = await res.arrayBuffer()
    return new NextResponse(buf, {
      status: 200,
      headers: {
        "Content-Type": "application/pdf",
        "Content-Disposition": `attachment; filename="report-${jobId}.pdf"`,
      },
    })
  } catch (e) {
    if (e instanceof Response) return e
    return NextResponse.json({ error: "Backend unavailable" }, { status: 503 })
  }
}