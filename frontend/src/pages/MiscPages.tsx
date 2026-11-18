import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, post } from '../api';
import { money, when } from '../components';
import { useApp } from '../store';

type EditConfig = { title: string; module: string; file: string; yaml: string; singleton?: boolean };

function YamlEditor({ value, onClose, onSaved }: { value: EditConfig; onClose: () => void; onSaved: () => void }) {
  const [text, setText] = useState(value.yaml);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const dirty = text !== value.yaml;
  async function save() {
    try {
      const url = value.singleton ? `/config/${value.module}` : `/config/${value.module}/${encodeURIComponent(value.file)}`;
      const r = await api(url, { method: 'PUT', headers: { 'Content-Type': 'text/plain' }, body: text });
      if (!r.ok) { setMsg({ ok: false, text: (r.errors || ['Configuration rejected']).join('\n') }); return; }
      setMsg({ ok: true, text: 'Saved and validated. Configuration reloaded.' }); onSaved();
    } catch (e: any) { setMsg({ ok: false, text: e.message }); }
  }
  async function validate() {
    try { const r = await api('/config/validate'); setMsg({ ok: r.ok, text: r.ok ? 'All configuration is valid.' : r.errors.join('\n') }); }
    catch (e: any) { setMsg({ ok: false, text: e.message }); }
  }
  function close() { if (dirty && !confirm('Discard unsaved changes?')) return; onClose(); }
  return <div className="panel">
    <div className="row between"><h3>{value.title}</h3><span className={'badge ' + (dirty ? 'warn' : 'ok')}>{dirty ? 'unsaved changes' : 'saved'}</span></div>
    <textarea aria-label="YAML configuration" className="code" rows={18} value={text} onChange={e => setText(e.target.value)} spellCheck={false} />
    <div className="row"><button className="btn primary" onClick={save}>Validate &amp; save</button><button className="btn" onClick={validate}>Validate all config</button><button className="btn" onClick={() => { setText(value.yaml); setMsg(null); }} disabled={!dirty}>Reset</button><button className="btn" onClick={close}>Cancel</button></div>
    {msg && <pre className={'result ' + (msg.ok ? 'okc' : 'badc')}>{msg.text}</pre>}
    <div className="muted small">YAML syntax is parsed by the backend. A failed full-config validation restores the saved file.</div>
  </div>;
}

const titles: Record<string, string> = { '/prompts': 'Prompts', '/tools': 'Tools', '/skills': 'Skills', '/models': 'Models' };
const modules: Record<string, string> = { '/prompts': 'prompts', '/tools': 'tools', '/skills': 'skills' };

export function ConfigList({ path, title, testable, single }: { path: string; title: string; testable?: boolean; single?: boolean }) {
  const [items, setItems] = useState<any>(null);
  const [search, setSearch] = useState('');
  const [editor, setEditor] = useState<EditConfig | null>(null);
  const [out, setOut] = useState<Record<string, string>>({});
  const [args, setArgs] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const load = () => { setError(''); api(path).then(setItems).catch(e => setError(e.message)); };
  useEffect(() => { load(); }, [path]);
  const list = useMemo(() => {
    const all = items == null ? [] : single ? [items] : items;
    return all.filter((x: any) => JSON.stringify(x).toLowerCase().includes(search.toLowerCase()));
  }, [items, search, single]);
  async function edit(it: any) {
    const module = path === '/guardrails' ? 'guardrails' : path === '/models' ? 'models' : modules[path];
    const file = path === '/models' ? 'models.yaml' : `${it.name}.yaml`;
    try {
      const source = await api('/config/source/' + module + (module === 'guardrails' || module === 'models' ? '' : '/' + encodeURIComponent(file)));
      setEditor({ title: `Edit ${title}: ${it.name || it.alias || title}`, module, file, yaml: source.yaml, singleton: module === 'guardrails' || module === 'models' });
    } catch (e: any) { setError(e.message); }
  }
  function create() {
    const module = modules[path];
    if (!module) return;
    const name = prompt(`New ${title.slice(0, -1).toLowerCase()} identifier (letters, digits, dashes)`);
    if (!name || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name)) { if (name) setError('Use letters, digits, dots, dashes, or underscores.'); return; }
    const snippets: Record<string, string> = {
      prompts: `name: ${name}\nversion: 1\nsystem: |\n  Describe the assistant behavior.\n`,
      tools: `name: ${name}\ntype: vector_search\ndescription: Describe this tool\ncollection: lob_content\ntop_k: 5\nparams:\n  query: { type: string, required: true }\n`,
      skills: `name: ${name}\ndescription: Describe this skill\ninstructions: |\n  Add skill instructions.\ntools: []\n`,
    };
    setEditor({ title: `New ${title.slice(0, -1)}: ${name}`, module, file: `${name}.yaml`, yaml: snippets[module] }); setError('');
  }
  async function test(name: string) {
    let a = {};
    try { a = args[name] ? JSON.parse(args[name]) : {}; } catch { setOut({ ...out, [name]: 'Args must be JSON, e.g. {"query":"monsoon"}' }); return; }
    try { const r = await post('/tools/' + name + '/test', a); setOut({ ...out, [name]: r.error ? 'ERROR: ' + r.error : r.output }); } catch (e: any) { setOut({ ...out, [name]: e.message }); }
  }
  return <div>
    <div className="row between"><div><h2>{title}</h2><div className="muted small">Validated configuration managed through the same YAML editor used for Agents.</div></div><div className="row">{modules[path] && <button className="btn primary" onClick={create}>＋ New {title.slice(0, -1).toLowerCase()}</button>}<input className="search" placeholder={`Search ${title.toLowerCase()}`} value={search} onChange={e => setSearch(e.target.value)} /></div></div>
    {error && <div className="banner bad">{error}</div>}
    {items == null && !error ? <div className="muted">Loading…</div> : list.map((it: any, i: number) => <div className="panel" key={it.name || it.alias || i}>
      <div className="row between"><div><h3>{it.name || it.alias || title}{it.type && <span className="badge info">{it.type}</span>}{it.version && <span className="muted small"> v{it.version}</span>}{it.live === false && <span className="badge warn">mock</span>}{it.live === true && <span className="badge ok">live</span>}</h3><div className="muted small">{it.description || it.provider || it.name || it.alias}</div></div>
        <div className="row">{(modules[path] || path === '/models' || path === '/guardrails') && <button className="btn" onClick={() => edit(it)}>Edit configuration</button>}</div></div>
      <pre>{JSON.stringify(it, null, 2)}</pre>
      {testable && it.type !== 'agent' && !it.requiresApproval && <div className="row"><input className="grow" placeholder='args JSON, e.g. {"query":"monsoon"}' value={args[it.name] || ''} onChange={e => setArgs({ ...args, [it.name]: e.target.value })} /><button className="btn" onClick={() => test(it.name)}>Test tool</button></div>}
      {out[it.name] && <pre className="result">{out[it.name]}</pre>}
    </div>)}
    {items && list.length === 0 && <div className="muted">No matching configuration.</div>}
    {editor && <YamlEditor value={editor} onClose={() => setEditor(null)} onSaved={load} />}
  </div>;
}

export function Knowledge() {
  const { tenant } = useApp();
  const [chunks, setChunks] = useState<any[]>([]); const [q, setQ] = useState(''); const [query, setQuery] = useState('');
  const [topK, setTopK] = useState(5); const [minScore, setMinScore] = useState(0); const [hits, setHits] = useState<any[] | null>(null); const [note, setNote] = useState(''); const [error, setError] = useState('');
  const load = () => api('/knowledge/chunks?q=' + encodeURIComponent(q)).then(setChunks).catch(e => setError(e.message));
  useEffect(() => { load(); }, [q, tenant]);
  async function search() { try { setHits(await post('/knowledge/search', { query, topK, minScore })); } catch (e: any) { setError(e.message); } }
  async function reindex() { if (!confirm('Re-embed every chunk with the current embedder?')) return; try { const r = await post('/admin/reindex'); setNote(`Re-indexed ${r.reindexed} chunks.`); load(); } catch (e: any) { setError(e.message); } }
  return <div><div className="row between"><div><h2>Knowledge</h2><div className="muted small">Search indexed educational material and inspect embedding status.</div></div><button className="btn" onClick={reindex}>Re-index with current embedder</button></div>
    {error && <div className="banner bad">{error}</div>}{note && <div className="banner">{note}</div>}
    <div className="panel"><h3>Retrieval tester</h3><div className="row"><input className="grow" placeholder="Type a question" value={query} onChange={e => setQuery(e.target.value)} onKeyDown={e => e.key === 'Enter' && search()} /><label className="small">top-k <input type="number" min={1} max={10} value={topK} onChange={e => setTopK(+e.target.value)} style={{ width: 50 }} /></label><label className="small">min score {minScore.toFixed(2)} <input type="range" min={0} max={1} step={0.05} value={minScore} onChange={e => setMinScore(+e.target.value)} /></label><button className="btn primary" onClick={search}>Search</button></div>{hits && (hits.length === 0 ? <div className="muted">No chunk above the min score.</div> : hits.map(h => <div key={h.id} className="hit"><b>{h.title}</b> <span className="badge info">{h.score}</span><div className="small">{h.text}</div></div>))}</div>
    <div className="panel"><div className="row between"><h3>Indexed content</h3><input className="search" placeholder="Search chunks" value={q} onChange={e => setQ(e.target.value)} /></div><table><thead><tr><th>Title</th><th>Content</th><th>Embed model</th><th>Version</th><th>Status</th></tr></thead><tbody>{chunks.map(c => <tr key={c.id}><td>{c.title}</td><td className="small">{c.text}</td><td className="small">{c.embed_model}</td><td>{c.embed_version}</td><td><span className="badge ok">indexed</span>{c.is_synthetic && <span className="badge">synthetic</span>}</td></tr>)}</tbody></table></div>
  </div>;
}

export function Evals() {
  const [rep, setRep] = useState<any>(null); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [sets, setSets] = useState<any[]>([]); const [editor, setEditor] = useState<EditConfig | null>(null);
  const load = () => api('/evals/config').then(setSets).catch(e => setError(e.message));
  useEffect(() => { api('/evals/latest').then(r => setRep(r && r.ranAt ? r : null)).catch(e => setError(e.message)); load(); }, []);
  async function run() { setBusy(true); try { setRep(await post('/evals/run')); } catch (e: any) { setError(e.message); } finally { setBusy(false); } }
  const m = rep?.metrics;
  return <div><div className="row between"><div><h2>Evals</h2><div className="muted small">Edit test cases and run the configured agent/retrieval evaluation.</div></div><div className="row"><button className="btn" onClick={() => { if (sets[0]) setEditor({ title: `Edit evaluation: ${sets[0].agent}`, module: 'evals', file: sets[0].file, yaml: sets[0].yaml }); }}>Edit eval YAML</button><button className="btn primary" disabled={busy} onClick={run}>{busy ? 'Running…' : 'Run evals'}</button></div></div>
    {error && <div className="banner bad">{error}</div>}{sets.map(s => <div className="panel" key={s.file}><b>{s.agent}</b><span className="muted small"> · {s.cases?.length || 0} cases</span></div>)}
    {!rep && <div className="muted">No report yet. Run evaluations to create a result report.</div>}{rep && <><div className="banner">{rep.baseline}</div><div className="cards"><div className="card"><div className="muted">Agent cases</div><b>{m.agentCasesPassed}/{m.agentCasesTotal}</b></div><div className="card"><div className="muted">Recall@5</div><b>{(m.retrievalRecallAt5 * 100).toFixed(0)}%</b></div><div className="card"><div className="muted">MRR</div><b>{m.retrievalMRR.toFixed(2)}</b></div><div className="card"><div className="muted">p95 latency</div><b>{m.p95LatencyMs} ms</b></div><div className="card"><div className="muted">Tokens</div><b>{m.totalTokens}</b></div><div className="card"><div className="muted">Cost</div><b>{money(m.totalCostUsd)}</b></div></div><div className="panel"><div><b>Confidence:</b> {rep.confidence}</div><div><b>Retrieval:</b> {rep.retrievalQualityClaim}</div><div><b>Gap:</b> {rep.gapToRealWorld}</div><div className="muted small">Ran {when(rep.ranAt)} · {m.retrievalPairs} retrieval pairs · {m.retrievalEmbedder}</div></div><table><thead><tr><th>Agent</th><th>Case</th><th>Result</th><th>Steps</th><th>Tokens</th><th>Time</th><th>Failures</th></tr></thead><tbody>{rep.cases.map((c: any) => <tr key={c.case}><td>{c.agent}</td><td><Link to={'/console/runs/' + c.runId}>{c.case}</Link></td><td><span className={'badge ' + (c.pass ? 'ok' : 'bad')}>{c.pass ? 'pass' : 'fail'}</span></td><td>{c.steps}</td><td>{c.tokens}</td><td>{c.ms} ms</td><td className="small">{c.failures.join('; ')}</td></tr>)}</tbody></table></>}
    {editor && <YamlEditor value={editor} onClose={() => setEditor(null)} onSaved={load} />}
  </div>;
}

export function Approvals() {
  const { tenant } = useApp(); const [rows, setRows] = useState<any[]>([]); const [status, setStatus] = useState('PENDING'); const [error, setError] = useState('');
  const load = () => api('/approvals?status=' + status).then(setRows).catch(e => setError(e.message)); useEffect(() => { load(); }, [tenant, status]);
  async function decide(id: string, ok: boolean) { try { await post('/approvals/' + id + '/decision', { approved: ok }); load(); } catch (e: any) { setError(e.message); } }
  return <div><div className="row between"><div><h2>Approvals</h2><div className="muted small">Review pending actions or inspect approval history.</div></div><select value={status} onChange={e => setStatus(e.target.value)}><option value="PENDING">Pending</option><option value="APPROVED">Approved</option><option value="REJECTED">Rejected</option></select></div>{error && <div className="banner bad">{error}</div>}
    {rows.length === 0 && <div className="muted">No {status.toLowerCase()} approvals.</div>}{rows.map(a => <div className="panel" key={a.id}><div className="row between"><div><b>{a.tool}</b> requested by <b>{a.agent}</b> · <Link to={'/console/runs/' + a.run_id}>run {a.run_id.slice(0, 8)}</Link> · {when(a.created_at)}</div><span className={'badge ' + (a.status === 'APPROVED' ? 'ok' : a.status === 'REJECTED' ? 'bad' : 'warn')}>{a.status}</span></div><div className="small muted">{a.input}</div><pre>{a.args}</pre>{status === 'PENDING' && <div className="row"><button className="btn primary" onClick={() => decide(a.id, true)}>Approve</button><button className="btn" onClick={() => decide(a.id, false)}>Reject</button></div>}</div>)}
  </div>;
}

export function Cost() {
  const { tenant } = useApp(); const [d, setD] = useState<any>(null); const [models, setModels] = useState<any[]>([]); const [editor, setEditor] = useState<EditConfig | null>(null); const [error, setError] = useState('');
  useEffect(() => { api('/cost').then(setD).catch(e => setError(e.message)); api('/models').then(setModels).catch(e => setError(e.message)); }, [tenant]);
  return <div><div className="row between"><div><h2>Cost &amp; Tokens</h2><div className="muted small">Model pricing and token controls alongside observed usage.</div></div><button className="btn" onClick={async () => { try { const r = await api('/config/source/models'); setEditor({ title: 'Edit model pricing and limits', module: 'models', file: 'models.yaml', yaml: r.yaml, singleton: true }); } catch (e: any) { setError(e.message); } }}>Edit model pricing</button></div>{error && <div className="banner bad">{error}</div>}
    {models.length > 0 && <div className="panel"><h3>Configured pricing</h3><table><thead><tr><th>Model</th><th>Provider</th><th>Identifier</th><th>Input / 1M</th><th>Output / 1M</th></tr></thead><tbody>{models.map(m => <tr key={m.alias}><td>{m.alias}</td><td>{m.provider}</td><td>{m.name}</td><td>{money(m.inputCostPerMillion)}</td><td>{money(m.outputCostPerMillion)}</td></tr>)}</tbody></table></div>}
    {!d ? <div className="muted">Loading usage…</div> : <><div className="banner">{d.note}</div><div className="two"><div className="panel"><h3>Tokens by agent</h3><pre>{JSON.stringify(d.byAgent, null, 2)}</pre></div><div className="panel"><h3>Calls by model</h3><pre>{JSON.stringify(d.byModel, null, 2)}</pre></div></div><div className="panel"><h3>Top 10 runs by tokens</h3><table><thead><tr><th>Run</th><th>Agent</th><th>Tokens</th><th>Cost</th><th>Status</th><th>Input</th></tr></thead><tbody>{d.topRuns.map((r: any) => <tr key={r.id}><td><Link to={'/console/runs/' + r.id}>{r.id.slice(0, 8)}</Link></td><td>{r.agent}</td><td>{r.total_tokens}</td><td>{money(r.cost_usd)}</td><td>{r.status}</td><td>{r.input}</td></tr>)}</tbody></table></div></>}{editor && <YamlEditor value={editor} onClose={() => setEditor(null)} onSaved={() => api('/models').then(setModels)} />}
  </div>;
}
