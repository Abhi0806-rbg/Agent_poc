export type Ident = { tenant: string; user: string; role: string };
let ident: Ident = { tenant: 'acme', user: 'u1', role: 'Developer' };
export const setIdent = (i: Ident) => { ident = i; };
const headers = () => ({ 'Content-Type': 'application/json', 'X-Tenant': ident.tenant, 'X-User': ident.user, 'X-Role': ident.role });

export async function api<T = any>(path: string, opts: RequestInit = {}): Promise<T> {
  const r = await fetch('/api' + path, { ...opts, headers: { ...headers(), ...((opts.headers as any) || {}) } });
  if (!r.ok) {
    let msg = await r.text();
    try { msg = JSON.parse(msg).message || msg; } catch { /* plain text */ }
    throw new Error(msg || r.statusText);
  }
  const t = await r.text();
  return (t ? JSON.parse(t) : null) as T;
}
export const post = (path: string, body?: any) => api(path, { method: 'POST', body: JSON.stringify(body ?? {}) });

/** Reads the SSE stream from POST /api/chat/stream and calls on(eventName, data). */
export async function streamChat(body: any, on: (ev: string, data: any) => void, signal?: AbortSignal) {
  const r = await fetch('/api/chat/stream', { method: 'POST', headers: headers(), body: JSON.stringify(body), signal });
  if (!r.ok || !r.body) throw new Error(await r.text());
  const rd = r.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done, value } = await rd.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let i: number;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, i);
      buf = buf.slice(i + 2);
      let ev = 'message';
      let data = '';
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) ev = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      if (data) { try { on(ev, JSON.parse(data)); } catch { /* ignore bad frame */ } }
    }
  }
}
