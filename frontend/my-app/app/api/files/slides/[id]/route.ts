import { NextRequest, NextResponse } from "next/server"
import { backendFetch } from "@/lib/backend"

export async function DELETE(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params
  const token = request.cookies.get("token")?.value
  if (!token) return NextResponse.json({ error: "Unauthorized" }, { status: 401 })

  const res = await backendFetch(`/api/files/slides/${id}`, { method: "DELETE", token })
  if (res.status === 204) return new NextResponse(null, { status: 204 })
  const text = await res.text()
  return new NextResponse(text, {
    status: res.status,
    headers: { "Content-Type": "application/json" },
  })
}
