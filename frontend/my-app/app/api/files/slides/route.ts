import { NextRequest, NextResponse } from 'next/server'

export async function GET(request: NextRequest) {
  const token = request.cookies.get('token')?.value
  if (!token) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  const backendRes = await fetch('http://localhost:8080/api/files/slides', {
    headers: { Authorization: `Bearer ${token}` },
  })

  const data = await backendRes.json()
  return NextResponse.json(data, { status: backendRes.status })
}