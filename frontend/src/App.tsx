import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { RealtimeProvider } from './context/RealtimeContext';
import { AppLayout } from './pages/AppLayout';
import { LoginPage } from './pages/LoginPage';
import { InboxPage } from './pages/InboxPage';
import { DashboardPage } from './pages/DashboardPage';
import { ActionItemsPage } from './pages/ActionItemsPage';
import { SettingsPage } from './pages/SettingsPage';

// Keep these paths in sync with SpaForwardController / SecurityConfig.SPA_ROUTES
// on the backend, which serve index.html for them in production.
export default function App() {
  return (
    <AuthProvider>
      <RealtimeProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<AppLayout />}>
            <Route path="/inbox" element={<InboxPage />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/action-items" element={<ActionItemsPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/" element={<Navigate to="/inbox" replace />} />
          </Route>
          <Route path="*" element={<Navigate to="/inbox" replace />} />
        </Routes>
      </RealtimeProvider>
    </AuthProvider>
  );
}
