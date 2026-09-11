import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';

function getBaseUrl(request: NextRequest): string {
  const host = request.headers.get('x-forwarded-host') || request.headers.get('host') || '15.252.16.216:3000';
  const proto = request.headers.get('x-forwarded-proto') || (host.includes('localhost') ? 'http' : 'http');
  return `${proto}://${host}`;
}

export async function GET(request: NextRequest) {
  const baseUrl = getBaseUrl(request);
  const clientId = process.env.GITHUB_CLIENT_ID || process.env.NEXT_PUBLIC_GITHUB_CLIENT_ID || 'Ov23liQRA1aEwV0iPyLo';

  if (!clientId) {
    return NextResponse.redirect(new URL('/login?error=github_client_id_missing', baseUrl));
  }

  const redirectUri = `${baseUrl}/api/auth/callback/github`;

  // Generate cryptographic CSRF state
  const state = crypto.randomBytes(24).toString('hex');

  const githubAuthUrl = new URL('https://github.com/login/oauth/authorize');
  githubAuthUrl.searchParams.set('client_id', clientId);
  githubAuthUrl.searchParams.set('redirect_uri', redirectUri);
  githubAuthUrl.searchParams.set('scope', 'read:user user:email');
  githubAuthUrl.searchParams.set('state', state);

  const response = NextResponse.redirect(githubAuthUrl.toString());

  const isHttps = baseUrl.startsWith('https://');

  // Store state in an HTTP-only cookie for CSRF verification
  response.cookies.set('safemigrate_oauth_state', state, {
    httpOnly: true,
    secure: isHttps,
    sameSite: 'lax',
    path: '/',
    maxAge: 60 * 10, // 10 minutes
  });

  return response;
}
