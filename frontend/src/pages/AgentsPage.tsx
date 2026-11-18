import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, post } from '../api';
import { money } from '../components';
import { useApp } from '../store';

const TEMPLATE = (n: string) => `name: ${n}
enabled: true
description: Describe what this agent does
model: fast
prompt: advisor@1
tools: [search_content]
suggestions: ["Ask me something"]
`;

export function AgentsPage() {
  const { agents, reloadAgents, setAgent } = useApp();
  const [sel, setSel] = useState<string | null>(null);
  const [yaml, setYaml] = useState('');
  const [detail, setDetail] = useState<any>(null);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [isNew, setIsNew] = useState(false);

  useEffect(() => {
    if (!sel || isNew) return;
    api('/agents/' + sel).then((d) => { setDetail(d); setYaml(d.yaml); setMsg(null); });
  }, [sel, isNew]);

  async function save() {
    const name = (yaml.match(/^name:\s*(\S+)/m) || [])[1];
    if (!name) { setMsg({ ok: false, text: 'YAML needs a name: line' }); return; }
    try {
      const r = await api('/agents/' + name + '/yaml', { method: 'PUT', headers: { 'Content-Type': 'text/plain' }, body: yaml });
      if (r.ok) { setMsg({ ok: true, text: 'Saved and validated. The agent is live now.' }); setIsNew(false); setSel(name); reloadAgents(); }
      else setMsg({ ok: false, text: r.errors.join('\n') });
    } catch (e: any) { setMsg({ ok: false, text: e.message }); }
  }
  async function validate() { const r = await api('/config/validate'); setMsg({ ok: r.ok, text: r.ok ? 'Config is valid.' : r.errors.join('\n') }); }
  async function toggle(name: string, on: boolean) {
    if (!on && !confirm(`Disable ${name}? Chats using it will stop working.`)) return;
    try { await post('/agents/' + name + '/enabled', { enabled: on }); reloadAgents(); } catch (e: any) { alert(e.message); }
  }

  return (
    <div>
      <div className="row between"><h2>Agents</h2>
        <button className="btn primary" onClick={() => { const n = prompt('New agent name (letters, digits, dashes)'); if (n) { setIsNew(true); setSel(n); setYaml(TEMPLATE(n)); setDetail(null); setMsg(null); } }}>＋ New agent</button></div>
      <table><thead><tr><th>Name</th><th>Model</th><th>Tools</th><th>Runs 24h</th><th>Success</th><th>Cost</th><th>Enabled</th><th /></tr></thead><tbody>
        {agents.map((a) => (
          <tr key={a.name} className={sel === a.name ? 'selrow' : ''}>
            <td><a href="#" onClick={(e) => { e.preventDefault(); setIsNew(false); setSel(a.name); }}>{a.name}</a><div className="muted small">{a.description}</div></td>
            <td>{a.model}</td><td className="small">{a.tools.join(', ')}</td><td>{a.runs24h}</td><td>{a.successRate}%</td><td>{money(a.cost24h)}</td>
            <td><input type="checkbox" checked={a.enabled} onChange={(e) => toggle(a.name, e.target.checked)} /></td>
            <td><Link to="/chat" onClick={() => setAgent(a.name)}>Chat</Link></td>
          </tr>))}
      </tbody></table>
      {sel && (
        <div className="panel">
          <h3>{isNew ? 'New agent: ' : 'Config: '}{sel}{detail && <span className="muted small"> · config hash {detail.configHash} · max steps {detail.limits.maxSteps}</span>}</h3>
          <textarea className="code" rows={16} value={yaml} onChange={(e) => setYaml(e.target.value)} spellCheck={false} />
          <div className="row"><button className="btn primary" onClick={save}>Validate &amp; save</button><button className="btn" onClick={validate}>Validate all config</button>
            <button className="btn" onClick={() => { setSel(null); setIsNew(false); }}>Close</button></div>
          {msg && <pre className={'result ' + (msg.ok ? 'okc' : 'badc')}>{msg.text}</pre>}
          <div className="muted small">Saving writes backend/config/agents/{'<name>'}.yaml. Invalid YAML is rejected and rolled back. Add prompts, tools and skills as files in the same config folder.</div>
        </div>)}
    </div>
  );
}
