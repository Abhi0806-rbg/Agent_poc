import { createContext, useCallback, useContext, useEffect, useState, ReactNode } from 'react';
import { api, setIdent } from './api';

export type Agent = { name: string; description: string; enabled: boolean; model: string; tools: string[]; skills: string[]; suggestions: string[]; runs24h: number; successRate: number; cost24h: number };
export type ChatItem = { id: string; title: string; agent: string; project?: string; pinned: boolean };
export const ROLES = ['Learner', 'Manager', 'Developer', 'Admin'];
export const TENANTS = ['acme', 'globex'];

type Ctx = {
  agents: Agent[]; agent: string; setAgent: (a: string) => void; reloadAgents: () => void;
  role: string; setRole: (r: string) => void; tenant: string; setTenant: (t: string) => void;
  dev: boolean; setDev: (d: boolean) => void; me: any;
  chats: ChatItem[]; reloadChats: () => void; canConsole: boolean; canDev: boolean;
};
const C = createContext<Ctx>(null as any);
export const useApp = () => useContext(C);

const ls = (k: string, d: string) => localStorage.getItem(k) || d;

export function AppProvider({ children }: { children: ReactNode }) {
  const [role, setRoleS] = useState(ls('role', 'Developer'));
  const [tenant, setTenantS] = useState(ls('tenant', 'acme'));
  const [agent, setAgentS] = useState(ls('agent', 'advisor'));
  const [dev, setDevS] = useState(ls('dev', '0') === '1');
  const [agents, setAgents] = useState<Agent[]>([]);
  const [chats, setChats] = useState<ChatItem[]>([]);
  const [me, setMe] = useState<any>(null);
  const [tick, setTick] = useState(0);
  setIdent({ tenant, user: 'u1', role });

  const reloadAgents = useCallback(() => { api('/agents').then(setAgents).catch(() => {}); }, []);
  const reloadChats = useCallback(() => { api('/chats').then(setChats).catch(() => {}); }, []);
  useEffect(() => { reloadAgents(); reloadChats(); api('/me').then(setMe).catch(() => {}); }, [role, tenant, tick, reloadAgents, reloadChats]);
  useEffect(() => { const t = setInterval(reloadAgents, 15000); return () => clearInterval(t); }, [reloadAgents]);

  const canConsole = role === 'Developer' || role === 'Admin';
  const value: Ctx = {
    agents, agent, reloadAgents, chats, reloadChats, me, role, tenant, dev: dev && canConsole, canConsole, canDev: canConsole,
    setAgent: (a) => { localStorage.setItem('agent', a); setAgentS(a); },
    setRole: (r) => { localStorage.setItem('role', r); setRoleS(r); setTick((x) => x + 1); },
    setTenant: (t) => { localStorage.setItem('tenant', t); setTenantS(t); setTick((x) => x + 1); },
    setDev: (d) => { localStorage.setItem('dev', d ? '1' : '0'); setDevS(d); },
  };
  return <C.Provider value={value}>{children}</C.Provider>;
}
