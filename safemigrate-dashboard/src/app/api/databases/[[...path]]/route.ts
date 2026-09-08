import { NextRequest, NextResponse } from 'next/server';

function getTargetUrl(req: NextRequest, pathArray?: string[]): string {
  // In docker, server is accessible at http://server:8080
  const serverBase = process.env.INTERNAL_BACKEND_HOST || (process.env.INTERNAL_BACKEND_URL ? process.env.INTERNAL_BACKEND_URL.replace(/\/api\/migrations$/, '') : 'http://localhost:8080');
  const base = `${serverBase}/api/databases`;
  const subpath = pathArray && pathArray.length > 0 ? '/' + pathArray.join('/') : '';
  const query = req.nextUrl.search;
  return `${base}${subpath}${query}`;
}

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ path?: string[] }> }
) {
  const resolvedParams = await params;
  const target = getTargetUrl(req, resolvedParams.path);
  try {
    const res = await fetch(target, {
      headers: { Accept: req.headers.get('accept') || 'application/json' },
      cache: 'no-store',
    });
    const contentType = res.headers.get('content-type') || 'application/json';
    const data = await res.text();
    return new Response(data, { status: res.status, headers: { 'Content-Type': contentType } });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 502 });
  }
}

export async function POST(
  req: NextRequest,
  { params }: { params: Promise<{ path?: string[] }> }
) {
  const resolvedParams = await params;
  const target = getTargetUrl(req, resolvedParams.path);
  try {
    const body = await req.text();
    const res = await fetch(target, {
      method: 'POST',
      headers: { 'Content-Type': req.headers.get('content-type') || 'application/json' },
      body: body || undefined,
    });
    const contentType = res.headers.get('content-type') || 'application/json';
    const data = await res.text();
    return new Response(data, { status: res.status, headers: { 'Content-Type': contentType } });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 502 });
  }
}

export async function DELETE(
  req: NextRequest,
  { params }: { params: Promise<{ path?: string[] }> }
) {
  const resolvedParams = await params;
  const target = getTargetUrl(req, resolvedParams.path);
  try {
    const res = await fetch(target, {
      method: 'DELETE',
      headers: { Accept: 'application/json' },
    });
    const contentType = res.headers.get('content-type') || 'application/json';
    const data = await res.text();
    return new Response(data, { status: res.status, headers: { 'Content-Type': contentType } });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 502 });
  }
}
