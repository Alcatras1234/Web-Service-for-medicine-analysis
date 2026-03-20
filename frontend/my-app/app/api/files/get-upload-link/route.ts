import { NextRequest, NextResponse } from 'next/server'

export async function GET(request: NextRequest) {
  const token = request.cookies.get('token')?.value
  if (!token) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  const filename = request.nextUrl.searchParams.get('filename')
  if (!filename) {
    return NextResponse.json({ error: 'filename required' }, { status: 400 })
  }

  const backendRes = await fetch(
    `http://localhost:8080/api/files/get-upload-link?filename=${encodeURIComponent(filename)}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    }
  )

  const data = await backendRes.json()
  return NextResponse.json(data, { status: backendRes.status })
}