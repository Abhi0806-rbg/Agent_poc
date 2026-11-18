import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, post, streamChat } from '../api';
import { TopBar } from '../components';
import { useApp } from '../store';

type Step = { kind: string; agent?: string; name: string; summary?: string; ms?: number };
type Msg = { role: 'user' | 'assistant' | 'error'; content: string; steps?: Step[]; runId?: string; meta?: any; approval?: any; streaming?: boolean };

export default function Chat() {
  const { id } = useParams();
  const nav = useNavigate();
  const { agents, agent, setAgent, chats, reloadChats, dev, canConsole } = useApp();
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [chatId, setChatId] = useState<string | undefined>(id);
  const [input, setInput] = useState('');
  const [mode, setMode] = useState<'fast' | 'smart'>('fast');
  const [busy, setBusy] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [q, setQ] = useState('');
  const abort = useRef<AbortController | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const cur = agents.find((a) => a.name === agent);

  useEffect(() => {
    if (!id) { setMsgs([]); setChatId(undefined); return; }
    api('/chats/' + id).then((c) => {
      setChatId(c.id);
      if (c.agent) setAgent(c.agent);
      setMsgs(c.messages.map((m: any) => ({ role: m.role, content: m.content, runId: m.run_id })));
    }).catch(() => nav('/chat'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);
  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [msgs]);

  const upd = (f: (m: Msg) => Msg) => setMsgs((ms) => ms.map((m, i) => (i === ms.length - 1 ? f(m) : m)));

  async function send(text: string) {
    if (!text.trim() || busy) return;
    setInput('');
    setBusy(true);
    setMsgs((m) => [...m, { role: 'user', content: text }, { role: 'assistant', content: '', steps: [], streaming: true }]);
    const ac = new AbortController();
    abort.current = ac;
    try {
      await streamChat({ chatId, agent, message: text, mode }, (ev, d) => {
        if (ev === 'chat') { setChatId(d.chatId); reloadChats(); }
        else if (ev === 'step') upd((m) => ({ ...m, steps: [...(m.steps || []), d] }));
        else if (ev === 'delta') upd((m) => ({ ...m, content: m.content + d.text }));
        else if (ev === 'approval') upd((m) => ({ ...m, approval: d }));
        else if (ev === 'done') upd((m) => ({
          ...m, streaming: false, runId: d.runId, meta: d,
          role: d.status === 'FAILED' || d.status === 'BUDGET_EXCEEDED' ? 'error' : 'assistant',
          content: m.content || d.text || '',
        }));
      }, ac.signal);
    } catch (e: any) {
      upd((m) => ({ ...m, role: 'error', streaming: false, content: e.name === 'AbortError' ? (m.content || 'Stopped.') : String(e.message) }));
    }
    setBusy(false);
    reloadChats();
  }

  async function decide(idx: number, ok: boolean) {
    const m = msgs[idx];
    try {
      const r = await post('/approvals/' + m.approval.approvalId + '/decision', { approved: ok });
      setMsgs((ms) => ms.map((x, i) => (i === idx ? {
        ...x, approval: { ...x.approval, decided: ok ? 'approved' : 'rejected' }, content: r.text || x.content,
        role: r.status === 'COMPLETED' ? 'assistant' : 'error', meta: { ...(x.meta || {}), tokens: r.tokens, cost: r.cost, ms: r.ms, status: r.status },
      } : x)));
    } catch (e: any) { alert(e.message); }
  }

  const shown = chats.filter((c) => c.title.toLowerCase().includes(q.toLowerCase()));
  const disabled = cur && !cur.enabled;

  return (
    <div className="app">
      {!collapsed && (
        <aside className="side">
          <div className="side-head"><b>Intutr Agents</b><button className="icon" onClick={() => setCollapsed(true)} title="Hide sidebar">⟨</button></div>
          <input className="search" placeholder="Search chats" value={q} onChange={(e) => setQ(e.target.value)} />
          <button className="nav active" onClick={() => nav('/chat')}>＋ New chat</button>
          {canConsole && <Link className="nav" to="/console/dashboard">⚙ Open Console</Link>}
          <div className="label">Agents</div>
          {agents.map((a) => (
            <button key={a.name} className={'nav small' + (a.name === agent ? ' sel' : '')} disabled={!a.enabled} onClick={() => setAgent(a.name)} title={a.description}>
              {a.name}{!a.enabled && ' (disabled)'}
            </button>
          ))}
          <div className="label">Recents</div>
          <div className="recents">
            {shown.length === 0 && <div className="muted pad">No chats yet</div>}
            {shown.map((c) => (
              <div key={c.id} className={'chatrow' + (c.id === id ? ' sel' : '')}>
                <Link to={'/chat/' + c.id}>{c.pinned ? '📌 ' : ''}{c.title}</Link>
                <span className="row-actions">
                  <button className="icon" title="Pin" onClick={async () => { await api('/chats/' + c.id, { method: 'PATCH', body: JSON.stringify({ pinned: !c.pinned }) }); reloadChats(); }}>★</button>
                  <button className="icon" title="Rename" onClick={async () => { const t = prompt('Rename chat', c.title); if (t) { await api('/chats/' + c.id, { method: 'PATCH', body: JSON.stringify({ title: t }) }); reloadChats(); } }}>✎</button>
                  <button className="icon" title="Delete" onClick={async () => { if (confirm('Delete this chat?')) { await api('/chats/' + c.id, { method: 'DELETE' }); reloadChats(); if (c.id === id) nav('/chat'); } }}>🗑</button>
                </span>
              </div>
            ))}
          </div>
        </aside>
      )}
      <main className="main">
        <TopBar left={collapsed ? <button className="icon" onClick={() => setCollapsed(false)}>☰</button> : undefined} />
        <div className="chat-scroll">
          {msgs.length === 0 ? (
            <div className="empty">
              <h1>{cur ? `Ask ${cur.name}` : 'Where should we begin?'}</h1>
              <div className="muted">{cur?.description}</div>
              {disabled && <div className="banner">This agent is disabled. Choose another agent, or enable it in the console.</div>}
            </div>
          ) : (
            <div className="thread">
              {msgs.map((m, i) => (
                <div key={i} className={'msg ' + m.role}>
                  {m.steps && m.steps.length > 0 && (
                    <details className="steps" open={m.streaming}>
                      <summary>{m.streaming ? 'Working…' : `${m.steps.length} steps`}</summary>
                      {m.steps.map((s, j) => <div key={j} className="chip">{s.kind === 'handoff' ? '→' : s.kind === 'tool' ? '🔧' : '🧠'} {s.agent ? s.agent + ' · ' : ''}{s.name} <span className="muted">{s.summary} {s.ms != null && `· ${s.ms}ms`}</span></div>)}
                    </details>
                  )}
                  <div className="bubble">{m.role === 'error' && '⚠ '}{m.content}{m.streaming && <span className="cursor">▍</span>}</div>
                  {m.approval && (
                    <div className="approval">
                      <b>Agent wants to run: {m.approval.tool}</b>
                      <pre>{JSON.stringify(m.approval.args, null, 2)}</pre>
                      {m.approval.decided ? <span className="badge ok">{m.approval.decided}</span> : (
                        <div className="row"><button className="btn primary" onClick={() => decide(i, true)}>Approve</button><button className="btn" onClick={() => decide(i, false)}>Reject</button></div>
                      )}
                    </div>
                  )}
                  {m.role !== 'user' && !m.streaming && m.content && (
                    <div className="actions">
                      <button className="icon" title="Copy" onClick={() => navigator.clipboard.writeText(m.content)}>⧉</button>
                      <button className="icon" title="Good">👍</button><button className="icon" title="Bad">👎</button>
                      <button className="icon" title="Regenerate" onClick={() => { const u = [...msgs].slice(0, i).reverse().find((x) => x.role === 'user'); if (u) send(u.content); }}>↻</button>
                    </div>
                  )}
                  {dev && m.runId && (
                    <div className="devmeta">{m.meta && `${m.meta.tokens ?? '?'} tokens · $${Number(m.meta.cost || 0).toFixed(4)} · ${m.meta.ms ?? '?'}ms · `}run <Link to={'/console/runs/' + m.runId}>{m.runId.slice(0, 8)}</Link> · Open trace</div>
                  )}
                </div>
              ))}
              <div ref={endRef} />
            </div>
          )}
        </div>
        <div className="composer-wrap">
          <div className="composer">
            <textarea rows={1} placeholder={disabled ? 'Agent disabled' : `Message ${agent}…`} value={input} disabled={!!disabled}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(input); } }} />
            <select className="pill" value={mode} onChange={(e) => setMode(e.target.value as any)} title="Fast uses the 'fast' model alias, Think uses 'smart'">
              <option value="fast">Fast</option><option value="smart">Think</option>
            </select>
            {busy ? <button className="send" onClick={() => abort.current?.abort()} title="Stop">■</button>
                  : <button className="send" disabled={!input.trim() || !!disabled} onClick={() => send(input)} title="Send">↑</button>}
          </div>
          {msgs.length === 0 && cur && cur.suggestions.length > 0 && (
            <div className="suggest">{cur.suggestions.map((s) => <button key={s} className="chipbtn" onClick={() => send(s)}>{s}</button>)}</div>
          )}
          <div className="hint">Try: "simulate error" (fallback), "simulate budget" (budget guard), or ask the report agent to email you (approval).</div>
        </div>
      </main>
    </div>
  );
}
