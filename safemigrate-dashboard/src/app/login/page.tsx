'use client';

import { useState } from 'react';
import { useAuth } from '@/context/AuthContext';

export default function LoginPage() {
  const { loginWithOAuth, isLoading } = useAuth();
  const [selectedProvider, setSelectedProvider] = useState<'github' | 'google' | 'okta' | null>(null);

  const handleLogin = async (provider: 'github' | 'google' | 'okta') => {
    setSelectedProvider(provider);
    await loginWithOAuth(provider);
  };

  return (
    <div className="min-h-screen w-full flex items-center justify-center p-6 bg-surface selection:bg-primary/30">
      {/* Glow effect in background */}
      <div className="absolute w-[600px] h-[600px] bg-primary/5 rounded-full blur-[140px] pointer-events-none -top-40 -left-40"></div>
      <div className="absolute w-[500px] h-[500px] bg-tertiary/5 rounded-full blur-[120px] pointer-events-none -bottom-20 -right-20"></div>

      <div className="w-full max-w-md rounded-2xl bg-surface-container-lowest border border-outline-variant/20 p-8 shadow-2xl relative z-10 flex flex-col gap-6">
        {/* Brand Header */}
        <div className="flex flex-col items-center text-center gap-3">
          <div className="w-14 h-14 rounded-2xl bg-surface-container flex items-center justify-center text-primary border border-outline-variant/30 shadow-inner">
            <span className="material-symbols-outlined text-[32px]">dataset</span>
          </div>
          <div>
            <div className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-mono bg-primary/10 text-primary border border-primary/20 mb-2">
              <span className="w-1.5 h-1.5 rounded-full bg-primary animate-ping"></span>
              Enterprise Database Control Plane
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-on-surface">SafeMigrate</h1>
            <p className="text-xs text-on-surface-variant mt-1 font-sans">
              Zero-downtime online PostgreSQL schema migrations with strict CDC replication
            </p>
          </div>
        </div>

        {/* OAuth Buttons Section */}
        <div className="flex flex-col gap-3">
          {/* GitHub OAuth */}
          <button
            onClick={() => handleLogin('github')}
            disabled={isLoading}
            className="w-full py-3 px-4 rounded-xl bg-surface-container hover:bg-surface-container-high border border-outline-variant/20 flex items-center justify-center gap-3 text-sm font-medium text-on-surface transition-all duration-200 hover:border-primary/40 hover:scale-[1.01] active:scale-[0.99] disabled:opacity-50 shadow-sm"
          >
            <svg className="w-5 h-5 fill-current text-white shrink-0" viewBox="0 0 24 24">
              <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z" />
            </svg>
            <span>
              {selectedProvider === 'github' && isLoading ? 'Connecting to GitHub...' : 'Continue with GitHub'}
            </span>
          </button>

          {/* Google OAuth */}
          <button
            onClick={() => handleLogin('google')}
            disabled={isLoading}
            className="w-full py-3 px-4 rounded-xl bg-surface-container hover:bg-surface-container-high border border-outline-variant/20 flex items-center justify-center gap-3 text-sm font-medium text-on-surface transition-all duration-200 hover:border-primary/40 hover:scale-[1.01] active:scale-[0.99] disabled:opacity-50 shadow-sm"
          >
            <svg className="w-5 h-5 shrink-0" viewBox="0 0 24 24">
              <path
                fill="#4285F4"
                d="M23.745 12.27c0-.7-.06-1.4-.19-2.07H12v4.51h6.6c-.29 1.52-1.14 2.82-2.4 3.68v3.05h3.88c2.27-2.09 3.66-5.17 3.66-9.17z"
              />
              <path
                fill="#34A853"
                d="M12 24c3.24 0 5.95-1.08 7.93-2.91l-3.88-3.05c-1.08.72-2.45 1.16-4.05 1.16-3.12 0-5.77-2.1-6.72-4.93H1.25v3.15C3.26 21.36 7.33 24 12 24z"
              />
              <path
                fill="#FBBC05"
                d="M5.28 14.27c-.25-.72-.38-1.49-.38-2.27s.13-1.55.38-2.27V6.58H1.25C.45 8.18 0 10.04 0 12s.45 3.82 1.25 5.42l4.03-3.15z"
              />
              <path
                fill="#EA4335"
                d="M12 4.75c1.77 0 3.35.61 4.6 1.8l3.42-3.42C17.95 1.19 15.24 0 12 0 7.33 0 3.26 2.64 1.25 6.58l4.03 3.15c.95-2.83 3.6-4.98 6.72-4.98z"
              />
            </svg>
            <span>
              {selectedProvider === 'google' && isLoading ? 'Connecting to Google...' : 'Continue with Google'}
            </span>
          </button>

          {/* Enterprise Okta / SSO */}
          <button
            onClick={() => handleLogin('okta')}
            disabled={isLoading}
            className="w-full py-3 px-4 rounded-xl bg-surface-container hover:bg-surface-container-high border border-outline-variant/20 flex items-center justify-center gap-3 text-sm font-medium text-on-surface transition-all duration-200 hover:border-primary/40 hover:scale-[1.01] active:scale-[0.99] disabled:opacity-50 shadow-sm"
          >
            <span className="material-symbols-outlined text-[20px] text-tertiary">key</span>
            <span>
              {selectedProvider === 'okta' && isLoading ? 'Redirecting to SSO...' : 'Single Sign-On (SAML / Okta)'}
            </span>
          </button>
        </div>

        {/* Divider */}
        <div className="flex items-center gap-3 text-[11px] font-mono text-outline-variant uppercase">
          <div className="h-[1px] flex-1 bg-outline-variant/20"></div>
          <span>Demo Quick Access</span>
          <div className="h-[1px] flex-1 bg-outline-variant/20"></div>
        </div>

        {/* One Click Instant SRE Demo Login */}
        <button
          onClick={() => handleLogin('github')}
          disabled={isLoading}
          className="w-full py-2.5 px-4 rounded-xl bg-primary/10 hover:bg-primary/20 text-primary border border-primary/30 flex items-center justify-center gap-2 text-xs font-mono font-medium transition-all hover:scale-[1.01] active:scale-[0.99]"
        >
          <span className="material-symbols-outlined text-[16px]">verified_user</span>
          <span>One-Click Sign In (Sara Chen · Staff SRE)</span>
        </button>

        {/* Footer Security Badges */}
        <div className="pt-2 border-t border-outline-variant/10 flex flex-col items-center gap-1.5 text-center text-[10px] text-on-surface-variant font-mono">
          <span className="flex items-center gap-1">
            <span className="material-symbols-outlined text-[13px] text-primary">lock</span>
            Encrypted TLS 1.3 · Mutual TLS Database Auth · SOC-2 Compliant
          </span>
          <span className="text-outline">SafeMigrate Platform Engine v2.4.0</span>
        </div>
      </div>
    </div>
  );
}
