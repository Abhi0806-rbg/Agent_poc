import { ReactNode } from 'react';
import { useApp, ROLES, TENANTS } from './store';

export function TopBar({ left }: { left?: ReactNode }) {
  const { agents, agent, setAgent, role, setRole, tenant, setTenant, dev, setDev, canDev, me } = useApp();
  return (
    <div className="top">
      <div className="row">{left}
        <select className="pill" value={agent} onChange={(e) => setAgent(e.target.value)} title="Active agent (shared by chat and console)">
          {agents.map((a) => <option key={a.name} value={a.name}>{a.enabled ? '● ' : '○ '}{a.name} · {a.model}</option>)}
        </select>
      </div>
      <div className="row">
        {me?.mockMode && <span className="badge warn" title="No API keys set: answers come from the mock provider">mock mode</span>}
        <span className="badge env">DEV</span>
        <select className="pill" value={tenant} onChange={(e) => setTenant(e.target.value)}>{TENANTS.map((t) => <option key={t}>{t}</option>)}</select>
        <select className="pill" value={role} onChange={(e) => setRole(e.target.value)}>{ROLES.map((r) => <option key={r}>{r}</option>)}</select>
        {canDev && <label className="toggle"><input type="checkbox" checked={dev} onChange={(e) => setDev(e.target.checked)} /> Dev mode</label>}
      </div>
    </div>
  );
}

export function Status({ s }: { s: string }) {
  const cls = s === 'COMPLETED' ? 'ok' : s === 'WAITING_APPROVAL' ? 'warn' : s === 'RUNNING' ? 'info' : 'bad';
  return <span className={'badge ' + cls}>{s}</span>;
}

export const money = (n: any) => '$' + Number(n || 0).toFixed(4);
export const when = (s: string) => (s ? new Date(s).toLocaleString() : '');
