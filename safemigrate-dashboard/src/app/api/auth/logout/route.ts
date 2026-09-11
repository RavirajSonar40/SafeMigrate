import { NextResponse } from 'next/server';

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

export async function GET(request: Request) {
  const response = NextResponse.redirect(new URL('/login', request.url));

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
