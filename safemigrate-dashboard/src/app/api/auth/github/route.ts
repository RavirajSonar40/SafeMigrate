import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';

export async function GET(request: NextRequest) {
  const clientId = process.env.GITHUB_CLIENT_ID || process.env.NEXT_PUBLIC_GITHUB_CLIENT_ID;

  if (!clientId) {
    return NextResponse.redirect(
      new URL('/login?error=github_client_id_missing', request.url)
    );
  }

  // Determine dynamic origin (handles localhost:3000, AWS EC2 IP, or custom domain)
  const host = request.headers.get('x-forwarded-host') || request.headers.get('host') || 'localhost:3000';
  const proto = request.headers.get('x-forwarded-proto') || (host.startsWith('localhost') ? 'http' : 'http');
  const redirectUri = `${proto}://${host}/api/auth/callback/github`;

  // Generate cryptographic CSRF state
  const state = crypto.randomBytes(24).toString('hex');

  const githubAuthUrl = new URL('https://github.com/login/oauth/authorize');
  githubAuthUrl.searchParams.set('client_id', clientId);
  githubAuthUrl.searchParams.set('redirect_uri', redirectUri);
  githubAuthUrl.searchParams.set('scope', 'read:user user:email');
  githubAuthUrl.searchParams.set('state', state);

  const response = NextResponse.redirect(githubAuthUrl.toString());

  // Store state in an HTTP-only cookie for CSRF verification
  response.cookies.set('safemigrate_oauth_state', state, {
    httpOnly: true,
    secure: proto === 'https',
    sameSite: 'lax',
    path: '/',
    maxAge: 60 * 10, // 10 minutes
  });

  return response;
}
