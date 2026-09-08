import type { Metadata } from 'next';
import { Geist, JetBrains_Mono } from 'next/font/google';
import './globals.css';
import Sidebar from '@/components/layout/Sidebar';
import Header from '@/components/layout/Header';

const geistSans = Geist({
  variable: '--font-geist-sans',
  subsets: ['latin'],
  display: 'swap',
});

const jetbrainsMono = JetBrains_Mono({
  variable: '--font-jetbrains-mono',
  subsets: ['latin'],
  display: 'swap',
});

export const metadata: Metadata = {
  title: 'SafeMigrate — Zero-Downtime PostgreSQL Schema Migrations',
  description: 'Mission-critical PostgreSQL schema migrations with zero locks, live CDC streaming, and guaranteed data integrity.',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={`dark h-full ${geistSans.variable} ${jetbrainsMono.variable}`}>
      <body className="bg-surface text-on-surface antialiased min-h-screen">
        <Sidebar />
        <div className="pl-[220px]">
          <Header />
          <main className="w-full pt-14 px-6 bg-surface min-h-screen">
            {children}
          </main>
        </div>
      </body>
    </html>
  );
}
