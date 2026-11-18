import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { TopBar } from '../components';
import { useApp } from '../store';
import { Dashboard, Cost } from './Dash';
import { AgentsPage } from './AgentsPage';
import { RunDetail, RunsPage } from './RunsPage';
import { Approvals, ConfigList, Evals, Knowledge } from './MiscPages';

const NAV = [
  ['dashboard', 'Dashboard'], ['agents', 'Agents'], ['runs', 'Runs'], ['prompts', 'Prompts'], ['tools', 'Tools'], ['skills', 'Skills'],
  ['knowledge', 'Knowledge'], ['evals', 'Evals'], ['cost', 'Cost & Tokens'], ['approvals', 'Approvals'], ['models', 'Models'], ['guardrails', 'Guardrails'],
];

export default function Console() {
  const { canConsole, role } = useApp();
  if (!canConsole) return <div className="denied"><h2>Console is for Developers and Admins</h2><p>Your role is {role}. Switch role in the top bar of the chat.</p><a href="/chat">Back to chat</a></div>;
  return (
    <div className="app">
      <aside className="side">
        <div className="side-head"><b>Agent Console</b></div>
        <NavLink className="nav" to="/chat">← Back to chat</NavLink>
        <div className="label">Console</div>
        {NAV.map(([p, label]) => <NavLink key={p} className={({ isActive }) => 'nav' + (isActive ? ' active' : '')} to={'/console/' + p}>{label}</NavLink>)}
      </aside>
      <main className="main">
        <TopBar />
        <div className="content">
          <Routes>
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="agents" element={<AgentsPage />} />
            <Route path="runs" element={<RunsPage />} />
            <Route path="runs/:id" element={<RunDetail />} />
            <Route path="prompts" element={<ConfigList path="/prompts" title="Prompts" />} />
            <Route path="tools" element={<ConfigList path="/tools" title="Tools" testable />} />
            <Route path="skills" element={<ConfigList path="/skills" title="Skills" />} />
            <Route path="models" element={<ConfigList path="/models" title="Models" />} />
            <Route path="guardrails" element={<ConfigList path="/guardrails" title="Guardrails" single />} />
            <Route path="knowledge" element={<Knowledge />} />
            <Route path="evals" element={<Evals />} />
            <Route path="cost" element={<Cost />} />
            <Route path="approvals" element={<Approvals />} />
            <Route path="*" element={<Navigate to="dashboard" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  );
}
