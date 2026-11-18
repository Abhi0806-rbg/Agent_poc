import { Navigate, Route, Routes } from 'react-router-dom';
import Chat from './pages/Chat';
import Console from './pages/Console';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/chat" replace />} />
      <Route path="/chat" element={<Chat />} />
      <Route path="/chat/:id" element={<Chat />} />
      <Route path="/console/*" element={<Console />} />
    </Routes>
  );
}
