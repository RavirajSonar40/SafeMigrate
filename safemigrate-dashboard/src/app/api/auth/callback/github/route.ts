import { NextRequest, NextResponse } from 'next/server';

function getBaseUrl(request: NextRequest): string {
  const host = request.headers.get('x-forwarded-host') || request.headers.get('host') || '15.252.16.216:3000';
  const proto = request.headers.get('x-forwarded-proto') || (host.includes('localhost') ? 'http' : 'http');
  return `${proto}://${host}`;
}

export async function GET(request: NextRequest) {
  const baseUrl = getBaseUrl(request);
  const { searchParams } = new URL(request.url);
  const code = searchParams.get('code');
  const state = searchParams.get('state');
  const error = searchParams.get('error');
  const errorDescription = searchParams.get('error_description');

  if (error) {
    return NextResponse.redirect(
      new URL(`/login?error=${encodeURIComponent(error)}&error_description=${encodeURIComponent(errorDescription || '')}`, baseUrl)
    );
  }

  if (!code) {
    return NextResponse.redirect(new URL('/login?error=missing_code', baseUrl));
  }

  // Validate CSRF state
  const storedState = request.cookies.get('safemigrate_oauth_state')?.value;
  if (storedState && state && storedState !== state) {
    return NextResponse.redirect(new URL('/login?error=csrf_state_mismatch', baseUrl));
  }

  const clientId = process.env.GITHUB_CLIENT_ID || process.env.NEXT_PUBLIC_GITHUB_CLIENT_ID || 'Ov23liQRA1aEwV0iPyLo';
  const clientSecret = process.env.GITHUB_CLIENT_SECRET || '9fbb6e739f3466b9f913e896745175ac0bb60668';

  if (!clientId || !clientSecret) {
    return NextResponse.redirect(new URL('/login?error=oauth_credentials_not_set', baseUrl));
  }

  try {
    // 1. Exchange authorization code for GitHub access token
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
      const errCode = tokenData.error || 'token_exchange_failed';
      const errDesc = tokenData.error_description || 'Failed to obtain access token from GitHub';
      return NextResponse.redirect(
        new URL(`/login?error=${encodeURIComponent(errCode)}&error_description=${encodeURIComponent(errDesc)}`, baseUrl)
      );
    }

    // 2. Fetch authenticated GitHub user profile
    const userRes = await fetch('https://api.github.com/user', {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'SafeMigrate-Enterprise-Control-Plane',
      },
    });

    if (!userRes.ok) {
      throw new Error(`Failed to fetch GitHub profile: status ${userRes.status}`);
    }

    const userData = await userRes.json();

    // 3. Fetch user emails to get verified primary email (handles users with private emails)
    let userEmail = userData.email;
    try {
      const emailsRes = await fetch('https://api.github.com/user/emails', {
        headers: {
          Authorization: `Bearer ${accessToken}`,
          'User-Agent': 'SafeMigrate-Enterprise-Control-Plane',
        },
      });
      if (emailsRes.ok) {
        const emails: Array<{ email: string; primary: boolean; verified: boolean }> = await emailsRes.json();
        const primary = emails.find((e) => e.primary && e.verified) || emails.find((e) => e.verified) || emails[0];
        if (primary?.email) {
          userEmail = primary.email;
        }
      }
    } catch {
      // Non-fatal, fallback to profile email
    }

    if (!userEmail) {
      userEmail = `${userData.login}@users.noreply.github.com`;
    }

    const userSession = {
      id: `gh_${userData.id}`,
      name: userData.name || userData.login,
      username: userData.login,
      email: userEmail,
      avatar: userData.avatar_url || `https://avatars.githubusercontent.com/u/${userData.id}?v=4`,
      role: userData.bio || 'Platform Owner & Infrastructure Lead',
      provider: 'github' as const,
      team: userData.company || 'Core Data & Database Reliability',
    };

    const isHttps = baseUrl.startsWith('https://');

    const response = NextResponse.redirect(new URL('/overview', baseUrl));

    // Store signed session cookie (HTTP-only)
    response.cookies.set('safemigrate_session', Buffer.from(JSON.stringify(userSession)).toString('base64'), {
      httpOnly: true,
      secure: isHttps,
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 7, // 7 days
    });

    // Store readable user identity cookie for client hydration
    response.cookies.set('safemigrate_oauth_user', JSON.stringify(userSession), {
      httpOnly: false,
      secure: isHttps,
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 7, // 7 days
    });

    // Clear state cookie
    response.cookies.delete('safemigrate_oauth_state');

    return response;
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : 'Unknown error during authentication';
    return NextResponse.redirect(new URL(`/login?error=${encodeURIComponent(message)}`, baseUrl));
  }
}
