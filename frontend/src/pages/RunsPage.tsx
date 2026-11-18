import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, post } from '../api';
import { Status, money, when } from '../components';
import { useApp } from '../store';

export function RunsPage() {
  const { agents, tenant } = useApp();
  const [rows, setRows] = useState<any[]>([]);
  const [agent, setAgent] = useState('');
  const [status, setStatus] = useState('');
  const load = () => api(`/runs?agent=${agent}&status=${status}&limit=100`).then(setRows);
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [agent, status, tenant]);
  return (
    <div>
      <div className="row between"><h2>Runs</h2>
        <div className="row">
          <select className="pill" value={agent} onChange={(e) => setAgent(e.target.value)}><option value="">All agents</option>{agents.map((a) => <option key={a.name}>{a.name}</option>)}</select>
          <select className="pill" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">All statuses</option>{['COMPLETED', 'FAILED', 'BUDGET_EXCEEDED', 'WAITING_APPROVAL', 'RUNNING'].map((s) => <option key={s}>{s}</option>)}</select>
          <button className="btn" onClick={load}>Refresh</button>
        </div></div>
      <table><thead><tr><th>Run</th><th>Agent</th><th>Status</th><th>Error</th><th>Tokens</th><th>Cost</th><th>Time</th><th>When</th><th>Input</th></tr></thead><tbody>
        {rows.map((r) => (
          <tr key={r.id}><td><Link to={'/console/runs/' + r.id}>{r.id.slice(0, 8)}</Link>{r.parent_run_id && <span className="muted small"> ↳ sub</span>}</td><td>{r.agent}</td><td><Status s={r.status} /></td><td>{r.error_type}</td>
            <td>{r.total_tokens}</td><td>{money(r.cost_usd)}</td><td>{r.duration_ms} ms</td><td>{when(r.created_at)}</td><td className="small">{r.input}</td></tr>))}
        {rows.length === 0 && <tr><td colSpan={9} className="muted">No runs match.</td></tr>}
      </tbody></table>
    </div>
  );
}

export function RunDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const [r, setR] = useState<any>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [replay, setReplay] = useState<any>(null);
  const [model, setModel] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { setR(null); setReplay(null); api('/runs/' + id).then(setR).catch(() => nav('/console/runs')); }, [id, nav]);
  if (!r) return <div className="muted">Loading…</div>;
  const total = Math.max(1, ...r.steps.map((s: any) => Number(s.duration_ms) || 0));

  async function doReplay() {
    setBusy(true);
    try { setReplay(await post(`/runs/${id}/replay`, { model })); } catch (e: any) { alert(e.message); }
    setBusy(false);
  }
  return (
    <div>
      <div className="row between"><h2>Run {r.id.slice(0, 8)} <Status s={r.status} /></h2><Link to="/console/runs">← All runs</Link></div>
      <div className="cards">
        <div className="card"><div className="muted">Agent</div><b>{r.agent}</b></div>
        <div className="card"><div className="muted">Tokens</div><b>{r.total_tokens}</b></div>
        <div className="card"><div className="muted">Cost</div><b>{money(r.cost_usd)}</b></div>
        <div className="card"><div className="muted">Duration</div><b>{r.duration_ms} ms</b></div>
        <div className="card"><div className="muted">Config hash</div><b className="small">{r.config_hash}</b></div>
      </div>
      <div className="muted small">correlation {r.correlation_id} {r.chat_id && <>· <Link to={'/chat/' + r.chat_id}>open chat</Link></>} {r.parent_run_id && <>· parent <Link to={'/console/runs/' + r.parent_run_id}>{r.parent_run_id.slice(0, 8)}</Link></>}</div>
      {r.error_type && <div className="banner bad">Error: <b>{r.error_type}</b> · {r.error_message}<br />
        <span className="muted">Fix layer: {({ provider_rate_limit: 'infrastructure (retry / fallback / limits)', provider_timeout: 'infrastructure', provider_5xx: 'infrastructure', schema_invalid: 'prompt or output format', tool_error: 'tool or data', max_steps: 'prompt / tool design / limits', budget_exceeded: 'limits or prompt size', loop_detected: 'prompt / tool design' } as any)[r.error_type] || 'check the steps below'}</span></div>}
      <div className="panel"><h3>Input</h3><pre>{r.input}</pre><h3>Output</h3><pre>{r.output || '—'}</pre></div>
      <div className="panel"><h3>Steps</h3>
        {r.steps.map((s: any, i: number) => (
          <div key={i}>
            <div className="step" onClick={() => setOpen(open === i ? null : i)}>
              <span className={'badge ' + (s.type === 'llm' ? 'info' : 'ok')}>{s.type}</span><span className="sn">{s.name}</span>
              <span className="bt"><i className={s.error ? 'errbar' : ''} style={{ width: Math.max(2, (Number(s.duration_ms) / total) * 100) + '%' }} /></span><span className="bv">{s.duration_ms} ms</span>
            </div>
            {open === i && <div className="stepdetail">{s.input && <><b>input</b><pre>{s.input}</pre></>}{s.output && <><b>output</b><pre>{s.output}</pre></>}{s.error && <><b>error</b><pre className="badc">{s.error}</pre></>}</div>}
          </div>))}
      </div>
      <div className="panel"><h3>LLM calls</h3>
        <table><thead><tr><th>Step</th><th>Provider</th><th>Model</th><th>In</th><th>Out</th><th>Cost</th><th>Latency</th><th>Finish</th><th>Error</th><th>Retry</th></tr></thead><tbody>
          {r.llmCalls.map((c: any, i: number) => <tr key={i}><td>{c.step_no}</td><td>{c.provider}</td><td>{c.model}</td><td>{c.prompt_tokens}{c.estimated && '~'}</td><td>{c.completion_tokens}{c.estimated && '~'}</td><td>{money(c.cost_usd)}</td><td>{c.latency_ms} ms</td><td>{c.finish_reason}</td><td>{c.error_type}</td><td>{c.retry_no}</td></tr>)}
        </tbody></table><div className="muted small">~ = estimated (mock provider)</div></div>
      {r.children.length > 0 && <div className="panel"><h3>Sub-agent runs</h3>{r.children.map((c: any) => <div key={c.id}><Link to={'/console/runs/' + c.id}>{c.id.slice(0, 8)}</Link> {c.agent} <Status s={c.status} /> {c.duration_ms} ms</div>)}</div>}
      <div className="panel"><h3>Replay</h3>
        <div className="row"><select className="pill" value={model} onChange={(e) => setModel(e.target.value)}><option value="">Same model</option><option value="fast">fast</option><option value="smart">smart</option></select>
          <button className="btn primary" disabled={busy} onClick={doReplay}>{busy ? 'Running…' : 'Replay this input'}</button></div>
        {replay && <div className="two"><div><b>Original</b><pre>{r.output || r.error_message}</pre><div className="muted small">{r.total_tokens} tokens · {money(r.cost_usd)} · {r.duration_ms} ms</div></div>
          <div><b>Replay</b> <Status s={replay.status} /><pre>{replay.text}</pre><div className="muted small">{replay.tokens} tokens · {money(replay.cost)} · {replay.ms} ms · <Link to={'/console/runs/' + replay.runId}>open trace</Link></div></div></div>}
      </div>
    </div>
  );
}
