'use client';

import { usePathname } from 'next/navigation';
import Sidebar from '@/components/layout/Sidebar';
import Header from '@/components/layout/Header';
import CommandPalette from '@/components/layout/CommandPalette';

export default function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isLoginPage = pathname === '/login';

  if (isLoginPage) {
    return <main className="w-full min-h-screen bg-surface">{children}</main>;
  }

  return (
    <div className="flex min-h-screen bg-surface">
      <Sidebar />
      <div className="flex-1 pl-[220px]">
        <Header />
        <main className="w-full pt-14 px-6 bg-surface min-h-screen">
          {children}
        </main>
      </div>
      <CommandPalette />
    </div>
  );
}
