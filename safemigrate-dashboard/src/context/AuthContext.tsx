'use client';

import React, { createContext, useContext, useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';

export interface UserSession {
  id: string;
  name: string;
  username: string;
  email: string;
  avatar: string;
  role: string;
  provider: 'github' | 'google' | 'okta';
  team: string;
}

interface AuthContextType {
  user: UserSession | null;
  isLoading: boolean;
  loginWithGitHubUsername: (username: string) => Promise<void>;
  loginWithOAuth: (provider: 'github' | 'google' | 'okta') => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserSession | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const router = useRouter();

  // Hydrate session from authentic server cookies or local storage
  useEffect(() => {
    let isMounted = true;

    async function hydrateSession() {
      try {
        const res = await fetch('/api/auth/me', { cache: 'no-store' });
        if (res.ok) {
          const data = await res.json();
          if (data.authenticated && data.user && isMounted) {
            setUser(data.user);
            localStorage.setItem('safemigrate_auth_user', JSON.stringify(data.user));
            setIsLoading(false);
            return;
          }
        }
      } catch {
        // Fallback to local storage check if API is unreachable
      }

      if (typeof window !== 'undefined' && isMounted) {
        try {
          const stored = localStorage.getItem('safemigrate_auth_user');
          if (stored) {
            const parsed = JSON.parse(stored);
            if (parsed && parsed.username && parsed.name !== 'Sara Chen') {
              setUser(parsed);
              setIsLoading(false);
              return;
            }
          }
        } catch {
          // ignore parsing error
        }
      }

      if (isMounted) {
        setUser(null);
        setIsLoading(false);
      }
    }

    hydrateSession();

    return () => {
      isMounted = false;
    };
  }, []);

  const loginWithGitHubUsername = async (username: string) => {
    setIsLoading(true);
    try {
      const cleanUser = username.trim().replace(/^@/, '');
      const res = await fetch(`https://api.github.com/users/${encodeURIComponent(cleanUser)}`);
      if (!res.ok) {
        throw new Error(`GitHub account @${cleanUser} not found.`);
      }
      const data = await res.json();
      const realUser: UserSession = {
        id: `gh_${data.id}`,
        name: data.name || data.login,
        username: data.login,
        email: data.email || `${data.login}@users.noreply.github.com`,
        avatar: data.avatar_url || `https://avatars.githubusercontent.com/u/${data.id}?v=4`,
        role: data.bio ? data.bio.slice(0, 45) : 'Database Infrastructure Engineer',
        provider: 'github',
        team: data.company || 'Core Data & Database Reliability',
      };
      setUser(realUser);
      localStorage.setItem('safemigrate_auth_user', JSON.stringify(realUser));
      router.push('/overview');
    } finally {
      setIsLoading(false);
    }
  };

  const loginWithOAuth = async (provider: 'github' | 'google' | 'okta') => {
    if (provider === 'github') {
      setIsLoading(true);
      // Genuine OAuth redirect to GitHub authorization endpoint
      window.location.href = '/api/auth/github';
      return;
    }

    // Google / Okta enterprise placeholder notice
    throw new Error(`${provider.toUpperCase()} enterprise SSO provider is coming soon. Please sign in with GitHub.`);
  };

  const logout = async () => {
    setIsLoading(true);
    try {
      await fetch('/api/auth/logout', { method: 'POST' });
    } catch {
      // Ignore network errors during logout
    }
    setUser(null);
    localStorage.removeItem('safemigrate_auth_user');
    setIsLoading(false);
    router.push('/login');
  };

  return (
    <AuthContext.Provider value={{ user, isLoading, loginWithGitHubUsername, loginWithOAuth, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
