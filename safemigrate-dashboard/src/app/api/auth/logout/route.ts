import { NextRequest, NextResponse } from 'next/server';

function getBaseUrl(request: Request | NextRequest): string {
  const headers = request.headers;
  const host = headers.get('x-forwarded-host') || headers.get('host') || '15.252.16.216:3000';
  const proto = headers.get('x-forwarded-proto') || (host.includes('localhost') ? 'http' : 'http');
  return `${proto}://${host}`;
}

export async function POST() {
  const response = NextResponse.json({ success: true });

  response.cookies.set('safemigrate_session', '', {
    httpOnly: true,
    path: '/',
    maxAge: 0,
  });

  response.cookies.set('safemigrate_oauth_user', '', {
    httpOnly: false,
    path: '/',
    maxAge: 0,
  });

  response.cookies.set('safemigrate_oauth_state', '', {
    httpOnly: true,
    path: '/',
    maxAge: 0,
  });

  return response;
}

export async function GET(request: NextRequest) {
  const baseUrl = getBaseUrl(request);
  const response = NextResponse.redirect(new URL('/login', baseUrl));

  response.cookies.set('safemigrate_session', '', {
    httpOnly: true,
    path: '/',
    maxAge: 0,
  });

  response.cookies.set('safemigrate_oauth_user', '', {
    httpOnly: false,
    path: '/',
    maxAge: 0,
  });

  response.cookies.set('safemigrate_oauth_state', '', {
    httpOnly: true,
    path: '/',
    maxAge: 0,
  });

  return response;
}
