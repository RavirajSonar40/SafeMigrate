'use client';

import React, { createContext, useContext, useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';

export interface UserSession {
  id: string;
  name: string;
  email: string;
  avatar: string;
  role: string;
  provider: 'github' | 'google' | 'okta';
  team: string;
}

interface AuthContextType {
  user: UserSession | null;
  isLoading: boolean;
  loginWithOAuth: (provider: 'github' | 'google' | 'okta') => Promise<void>;
  logout: () => void;
}

const DEFAULT_USERS: Record<'github' | 'google' | 'okta', UserSession> = {
  github: {
    id: 'gh_984120',
    name: 'Sara Chen',
    email: 'sara.chen@enterprise.internal',
    avatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&auto=format&fit=crop&q=80',
    role: 'Staff Infrastructure SRE',
    provider: 'github',
    team: 'Database Reliability & Platform',
  },
  google: {
    id: 'ggl_104829',
    name: 'Alex Rivera',
    email: 'alex.rivera@company.com',
    avatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100&auto=format&fit=crop&q=80',
    role: 'Principal Systems Architect',
    provider: 'google',
    team: 'Core Data Infrastructure',
  },
  okta: {
    id: 'sso_492019',
    name: 'Morgan Taylor',
    email: 'morgan.taylor@globalcorp.io',
    avatar: 'https://images.unsplash.com/photo-1517841905240-472988babdf9?w=100&auto=format&fit=crop&q=80',
    role: 'Lead Cloud Database Administrator',
    provider: 'okta',
    team: 'Enterprise Database Governance',
  },
};

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserSession | null>(() => {
    if (typeof window !== 'undefined') {
      try {
        const stored = localStorage.getItem('safemigrate_auth_user');
        if (stored) {
          return JSON.parse(stored);
        }
        localStorage.setItem('safemigrate_auth_user', JSON.stringify(DEFAULT_USERS.github));
        return DEFAULT_USERS.github;
      } catch {
        return DEFAULT_USERS.github;
      }
    }
    return DEFAULT_USERS.github;
  });
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const router = useRouter();

  useEffect(() => {
    // Keep localStorage synchronized on client mount if missing
    try {
      if (!localStorage.getItem('safemigrate_auth_user')) {
        localStorage.setItem('safemigrate_auth_user', JSON.stringify(DEFAULT_USERS.github));
      }
    } catch {
      // Ignore storage errors in restricted contexts
    }
  }, []);

  const loginWithOAuth = async (provider: 'github' | 'google' | 'okta') => {
    setIsLoading(true);
    // Simulate authentic OAuth 2.0 PKCE exchange handshake
    await new Promise((resolve) => setTimeout(resolve, 800));

    const loggedInUser = DEFAULT_USERS[provider];
    setUser(loggedInUser);
    localStorage.setItem('safemigrate_auth_user', JSON.stringify(loggedInUser));
    setIsLoading(false);
    router.push('/overview');
  };

  const logout = () => {
    setUser(null);
    localStorage.removeItem('safemigrate_auth_user');
    router.push('/login');
  };

  return (
    <AuthContext.Provider value={{ user, isLoading, loginWithOAuth, logout }}>
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
