import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { money, when } from '../components';
import { useApp } from '../store';

function Bars({ rows, label, value, fmt }: { rows: any[]; label: string; value: string; fmt?: (n: number) => string }) {
  const max = Math.max(1, ...rows.map((r) => Number(r[value]) || 0));
  if (rows.length === 0) return <div className="muted">No data</div>;
  return <div>{rows.map((r, i) => (
    <div key={i} className="bar"><span className="bl">{r[label]}</span><span className="bt"><i style={{ width: (Number(r[value]) / max) * 100 + '%' }} /></span><span className="bv">{fmt ? fmt(Number(r[value])) : r[value]}</span></div>
  ))}</div>;
}

export function Dashboard() {
  const { tenant } = useApp();
  const [d, setD] = useState<any>(null);
  useEffect(() => { api('/dashboard').then(setD); }, [tenant]);
  if (!d) return <div className="muted">Loading…</div>;
  const k = d.kpi;
  const byAgent: Record<string, number> = {};
  d.costByAgentDay.forEach((r: any) => { byAgent[r.agent] = (byAgent[r.agent] || 0) + Number(r.cost); });
  return (
    <div>
      <h2>Dashboard <span className="muted small">last 24h · {tenant}</span></h2>
      <div className="cards">
        <div className="card"><div className="muted">Runs</div><b>{k.runs}</b></div>
        <div className="card"><div className="muted">Success rate</div><b>{k.success_rate}%</b></div>
        <div className="card"><div className="muted">p95 latency</div><b>{k.p95_ms} ms</b></div>
        <div className="card"><div className="muted">Tokens</div><b>{k.tokens}</b></div>
        <div className="card"><div className="muted">Cost</div><b>{money(k.cost)}</b></div>
        <div className="card"><div className="muted">Config</div><b className={d.configOk ? 'okc' : 'badc'}>{d.configOk ? 'valid' : 'errors'}</b></div>
      </div>
      <div className="two">
        <div className="panel"><h3>Cost by agent (7 days)</h3><Bars rows={Object.entries(byAgent).map(([agent, cost]) => ({ agent, cost }))} label="agent" value="cost" fmt={money} /></div>
        <div className="panel"><h3>Errors by type (7 days)</h3><Bars rows={d.errorsByType} label="error_type" value="n" /></div>
      </div>
      <div className="panel"><h3>Recent failed runs</h3>
        <table><thead><tr><th>Run</th><th>Agent</th><th>Error</th><th>When</th></tr></thead><tbody>
          {d.recentFailed.map((r: any) => <tr key={r.id}><td><Link to={'/console/runs/' + r.id}>{r.id.slice(0, 8)}</Link></td><td>{r.agent}</td><td>{r.error_type}</td><td>{when(r.created_at)}</td></tr>)}
        </tbody></table></div>
      {d.pendingApprovals > 0 && <div className="banner">{d.pendingApprovals} approval(s) waiting. <Link to="/console/approvals">Review</Link></div>}
    </div>
  );
}

export function Cost() {
  const { tenant } = useApp();
  const [d, setD] = useState<any>(null);
  useEffect(() => { api('/cost').then(setD); }, [tenant]);
  if (!d) return <div className="muted">Loading…</div>;
  return (
    <div>
      <h2>Cost &amp; Tokens</h2>
      <div className="banner">{d.note}</div>
      <div className="two">
        <div className="panel"><h3>Tokens by agent</h3><Bars rows={d.byAgent} label="agent" value="tokens" /></div>
        <div className="panel"><h3>Calls by model</h3><Bars rows={d.byModel.map((r: any) => ({ m: r.provider + ' / ' + r.model, calls: r.calls }))} label="m" value="calls" /></div>
      </div>
      <div className="panel"><h3>Top 10 runs by tokens</h3>
        <table><thead><tr><th>Run</th><th>Agent</th><th>Tokens</th><th>Cost</th><th>Status</th><th>Input</th></tr></thead><tbody>
          {d.topRuns.map((r: any) => <tr key={r.id}><td><Link to={'/console/runs/' + r.id}>{r.id.slice(0, 8)}</Link></td><td>{r.agent}</td><td>{r.total_tokens}</td><td>{money(r.cost_usd)}</td><td>{r.status}</td><td>{r.input}</td></tr>)}
        </tbody></table></div>
    </div>
  );
}
