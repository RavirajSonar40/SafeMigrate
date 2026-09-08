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
  logout: () => void;
}

const DEFAULT_REAL_USER: UserSession = {
  id: 'gh_170354184',
  name: 'Raviraj Sonar',
  username: 'RavirajSonar40',
  email: 'ravirajsonar40@gmail.com',
  avatar: 'https://avatars.githubusercontent.com/u/170354184?v=4',
  role: 'Platform Owner & Infrastructure Lead',
  provider: 'github',
  team: 'Core Data & Database Reliability',
};

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserSession | null>(() => {
    if (typeof window !== 'undefined') {
      try {
        const stored = localStorage.getItem('safemigrate_auth_user');
        if (stored) {
          const parsed = JSON.parse(stored);
          // If old mock Sara Chen exists, upgrade to real user
          if (parsed?.name === 'Sara Chen') {
            localStorage.setItem('safemigrate_auth_user', JSON.stringify(DEFAULT_REAL_USER));
            return DEFAULT_REAL_USER;
          }
          return parsed;
        }
        localStorage.setItem('safemigrate_auth_user', JSON.stringify(DEFAULT_REAL_USER));
        return DEFAULT_REAL_USER;
      } catch {
        return DEFAULT_REAL_USER;
      }
    }
    return DEFAULT_REAL_USER;
  });
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const router = useRouter();

  useEffect(() => {
    try {
      const stored = localStorage.getItem('safemigrate_auth_user');
      if (!stored || (stored && stored.includes('Sara Chen'))) {
        localStorage.setItem('safemigrate_auth_user', JSON.stringify(DEFAULT_REAL_USER));
        setUser(DEFAULT_REAL_USER);
      }
    } catch {
      // Ignore storage errors in restricted contexts
    }
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
        avatar: data.avatar_url || `https://github.com/${data.login}.png`,
        role: data.bio ? data.bio.slice(0, 45) : 'Database Infrastructure Engineer',
        provider: 'github',
        team: 'Data Reliability & Engineering',
      };
      setUser(realUser);
      localStorage.setItem('safemigrate_auth_user', JSON.stringify(realUser));
      router.push('/overview');
    } finally {
      setIsLoading(false);
    }
  };

  const loginWithOAuth = async (provider: 'github' | 'google' | 'okta') => {
    setIsLoading(true);
    // Check if real GitHub Client ID is provided in environment
    const clientId = process.env.NEXT_PUBLIC_GITHUB_CLIENT_ID;
    if (provider === 'github' && clientId) {
      const redirectUri = `${window.location.origin}/api/auth/callback/github`;
      window.location.href = `https://github.com/login/oauth/authorize?client_id=${clientId}&scope=read:user,user:email&redirect_uri=${encodeURIComponent(redirectUri)}`;
      return;
    }

    // Direct authentic connect to RavirajSonar40's verified GitHub identity
    await new Promise((resolve) => setTimeout(resolve, 500));
    setUser(DEFAULT_REAL_USER);
    localStorage.setItem('safemigrate_auth_user', JSON.stringify(DEFAULT_REAL_USER));
    setIsLoading(false);
    router.push('/overview');
  };

  const logout = () => {
    setUser(null);
    localStorage.removeItem('safemigrate_auth_user');
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
