import { NextRequest, NextResponse } from 'next/server';

export async function GET(request: NextRequest) {
  // Check HTTP-only session cookie first
  const sessionCookie = request.cookies.get('safemigrate_session')?.value;
  if (sessionCookie) {
    try {
      const decoded = Buffer.from(sessionCookie, 'base64').toString('utf-8');
      const user = JSON.parse(decoded);
      return NextResponse.json({ authenticated: true, user });
    } catch {
      // If parsing fails, fall through to check readable cookie
    }
  }

  // Fallback check on safemigrate_oauth_user cookie
  const userCookie = request.cookies.get('safemigrate_oauth_user')?.value;
  if (userCookie) {
    try {
      const user = JSON.parse(userCookie);
      return NextResponse.json({ authenticated: true, user });
    } catch {
      // Invalid JSON
    }
  }

  return NextResponse.json({ authenticated: false, user: null }, { status: 401 });
}
