import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, post } from '../api';
import { money, when } from '../components';
import { useApp } from '../store';

export function ConfigList({ path, title, testable, single }: { path: string; title: string; testable?: boolean; single?: boolean }) {
  const [items, setItems] = useState<any>(null);
  const [out, setOut] = useState<Record<string, string>>({});
  const [args, setArgs] = useState<Record<string, string>>({});
  useEffect(() => { api(path).then(setItems); }, [path]);
  if (!items) return <div className="muted">Loading…</div>;
  const list = single ? [items] : items;
  async function test(name: string) {
    let a = {};
    try { a = args[name] ? JSON.parse(args[name]) : {}; } catch { setOut({ ...out, [name]: 'Args must be JSON, e.g. {"query":"monsoon"}' }); return; }
    const r = await post('/tools/' + name + '/test', a);
    setOut({ ...out, [name]: r.error ? 'ERROR: ' + r.error : r.output });
  }
  return (
    <div><h2>{title}</h2><div className="muted small">Read-only view of the YAML in backend/config. Edit the files (or agents via the Agents page) and validate.</div>
      {list.map((it: any, i: number) => (
        <div className="panel" key={i}>
          <h3>{it.name || it.alias || title}{it.type && <span className="badge info">{it.type}</span>}{it.version && <span className="muted small"> v{it.version}</span>}{it.live === false && <span className="badge warn">mock</span>}{it.live === true && <span className="badge ok">live</span>}</h3>
          <pre>{JSON.stringify(it, null, 2)}</pre>
          {testable && it.type !== 'agent' && !it.requiresApproval && (
            <div className="row"><input className="grow" placeholder='args JSON, e.g. {"query":"monsoon"}' value={args[it.name] || ''} onChange={(e) => setArgs({ ...args, [it.name]: e.target.value })} />
              <button className="btn" onClick={() => test(it.name)}>Test tool</button></div>)}
          {out[it.name] && <pre className="result">{out[it.name]}</pre>}
        </div>))}
    </div>
  );
}

export function Knowledge() {
  const { tenant } = useApp();
  const [chunks, setChunks] = useState<any[]>([]);
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [topK, setTopK] = useState(5);
  const [minScore, setMinScore] = useState(0);
  const [hits, setHits] = useState<any[] | null>(null);
  const [note, setNote] = useState('');
  useEffect(() => { api('/knowledge/chunks?q=' + encodeURIComponent(q)).then(setChunks); }, [q, tenant]);
  async function search() { setHits(await post('/knowledge/search', { query, topK, minScore })); }
  async function reindex() { if (!confirm('Re-embed every chunk with the current embedder?')) return; const r = await post('/admin/reindex'); setNote(`Re-indexed ${r.reindexed} chunks.`); setQ(q + ' '); setQ(q); }
  return (
    <div>
      <div className="row between"><h2>Knowledge</h2><button className="btn" onClick={reindex}>Re-index with current embedder</button></div>
      {note && <div className="banner">{note}</div>}
      <div className="panel"><h3>Retrieval tester</h3>
        <div className="row"><input className="grow" placeholder="Type a question" value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && search()} />
          <label className="small">top-k <input type="number" min={1} max={10} value={topK} onChange={(e) => setTopK(+e.target.value)} style={{ width: 50 }} /></label>
          <label className="small">min score {minScore.toFixed(2)} <input type="range" min={0} max={1} step={0.05} value={minScore} onChange={(e) => setMinScore(+e.target.value)} /></label>
          <button className="btn primary" onClick={search}>Search</button></div>
        {hits && (hits.length === 0 ? <div className="muted">No chunk above the min score.</div> : hits.map((h) => <div key={h.id} className="hit"><b>{h.title}</b> <span className="badge info">{h.score}</span><div className="small">{h.text}</div></div>))}
      </div>
      <div className="panel"><h3>Chunks <input className="search inline" placeholder="Filter" value={q} onChange={(e) => setQ(e.target.value)} /></h3>
        <table><thead><tr><th>Title</th><th>Text</th><th>Embed model</th><th>v</th></tr></thead><tbody>
          {chunks.map((c) => <tr key={c.id}><td>{c.title}{c.is_synthetic && <span className="badge">synthetic</span>}</td><td className="small">{c.text}</td><td className="small">{c.embed_model}</td><td>{c.embed_version}</td></tr>)}
        </tbody></table></div>
    </div>
  );
}

export function Evals() {
  const [rep, setRep] = useState<any>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { api('/evals/latest').then((r) => setRep(r && r.ranAt ? r : null)); }, []);
  async function run() { setBusy(true); try { setRep(await post('/evals/run')); } catch (e: any) { alert(e.message); } setBusy(false); }
  const m = rep?.metrics;
  return (
    <div>
      <div className="row between"><h2>Evals</h2><button className="btn primary" disabled={busy} onClick={run}>{busy ? 'Running…' : 'Run evals'}</button></div>
      {!rep && <div className="muted">No report yet. Click Run evals (cases live in backend/config/evals).</div>}
      {rep && (<>
        <div className="banner">{rep.baseline}</div>
        <div className="cards">
          <div className="card"><div className="muted">Agent cases passed</div><b>{m.agentCasesPassed}/{m.agentCasesTotal}</b></div>
          <div className="card"><div className="muted">Recall@5</div><b>{(m.retrievalRecallAt5 * 100).toFixed(0)}%</b></div>
          <div className="card"><div className="muted">MRR</div><b>{m.retrievalMRR.toFixed(2)}</b></div>
          <div className="card"><div className="muted">p95 latency</div><b>{m.p95LatencyMs} ms</b></div>
          <div className="card"><div className="muted">Tokens</div><b>{m.totalTokens}</b></div>
          <div className="card"><div className="muted">Cost</div><b>{money(m.totalCostUsd)}</b></div>
        </div>
        <div className="panel"><div><b>Confidence:</b> {rep.confidence}</div><div><b>Gap to real world:</b> {rep.gapToRealWorld}</div><div className="muted small">Ran {when(rep.ranAt)} · {m.retrievalPairs} retrieval pairs</div></div>
        <table><thead><tr><th>Agent</th><th>Case</th><th>Result</th><th>Steps</th><th>Tokens</th><th>Time</th><th>Failures</th></tr></thead><tbody>
          {rep.cases.map((c: any) => <tr key={c.case}><td>{c.agent}</td><td><Link to={'/console/runs/' + c.runId}>{c.case}</Link></td><td><span className={'badge ' + (c.pass ? 'ok' : 'bad')}>{c.pass ? 'pass' : 'fail'}</span></td><td>{c.steps}</td><td>{c.tokens}</td><td>{c.ms} ms</td><td className="small">{c.failures.join('; ')}</td></tr>)}
        </tbody></table></>)}
    </div>
  );
}

export function Approvals() {
  const { tenant } = useApp();
  const [rows, setRows] = useState<any[]>([]);
  const load = () => api('/approvals').then(setRows);
  useEffect(() => { load(); }, [tenant]);
  async function decide(id: string, ok: boolean) { try { await post('/approvals/' + id + '/decision', { approved: ok }); load(); } catch (e: any) { alert(e.message); } }
  return (
    <div><h2>Approvals</h2>
      {rows.length === 0 && <div className="muted">Nothing waiting. In chat, ask the report agent: "Email me the weekly batch summary".</div>}
      {rows.map((a) => (
        <div className="panel" key={a.id}><b>{a.tool}</b> requested by <b>{a.agent}</b> · <Link to={'/console/runs/' + a.run_id}>run {a.run_id.slice(0, 8)}</Link> · {when(a.created_at)}
          <div className="small muted">{a.input}</div><pre>{a.args}</pre>
          <div className="row"><button className="btn primary" onClick={() => decide(a.id, true)}>Approve</button><button className="btn" onClick={() => decide(a.id, false)}>Reject</button></div></div>))}
    </div>
  );
}
