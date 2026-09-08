import { NextRequest, NextResponse } from 'next/server';

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const code = searchParams.get('code');

  if (!code) {
    return NextResponse.redirect(new URL('/login?error=missing_code', request.url));
  }

  const clientId = process.env.GITHUB_CLIENT_ID || process.env.NEXT_PUBLIC_GITHUB_CLIENT_ID;
  const clientSecret = process.env.GITHUB_CLIENT_SECRET;

  if (!clientId || !clientSecret) {
    // If secrets aren't set in container env, redirect back with notice
    return NextResponse.redirect(new URL('/login?error=oauth_credentials_not_set', request.url));
  }

  try {
    // 1. Exchange code for access token
    const tokenRes = await fetch('https://github.com/login/oauth/access_token', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({
        client_id: clientId,
        client_secret: clientSecret,
        code,
      }),
    });

    const tokenData = await tokenRes.json();
    const accessToken = tokenData.access_token;

    if (!accessToken) {
      return NextResponse.redirect(new URL('/login?error=token_exchange_failed', request.url));
    }

    // 2. Fetch authenticated user profile
    const userRes = await fetch('https://api.github.com/user', {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'SafeMigrate-Platform',
      },
    });

    const userData = await userRes.json();

    const response = NextResponse.redirect(new URL('/overview', request.url));
    // Set a lightweight cookie with user identity for hydration
    response.cookies.set('safemigrate_oauth_user', JSON.stringify({
      id: `gh_${userData.id}`,
      name: userData.name || userData.login,
      username: userData.login,
      email: userData.email || `${userData.login}@users.noreply.github.com`,
      avatar: userData.avatar_url,
      role: userData.bio || 'Platform Engineer',
      provider: 'github',
      team: 'Database Reliability',
    }), { path: '/', maxAge: 60 * 60 * 24 * 7 });

    return response;
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : 'Unknown error';
    return NextResponse.redirect(new URL(`/login?error=${encodeURIComponent(message)}`, request.url));
  }
}
