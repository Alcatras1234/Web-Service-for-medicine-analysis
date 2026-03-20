"proxy"
import { NextRequest, NextResponse } from 'next/server'

const PROTECTED = ['/dashboard', '/upload']

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl
  const isProtected = PROTECTED.some(path => pathname.startsWith(path))

  if (isProtected) {
    const token = request.cookies.get('token')?.value
    if (!token) {
      const loginUrl = new URL('/', request.url)
      loginUrl.searchParams.set('from', pathname)
      return NextResponse.redirect(loginUrl)
    }
  }

  return NextResponse.next()
}

export const config = {
  matcher: ['/dashboard/:path*', '/upload/:path*'],
}
