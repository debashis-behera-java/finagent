import { Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { RedirectIfSignedIn, RequireAuth } from './components/RequireAuth';
import { DashboardPage } from './pages/DashboardPage';
import { NewResearchPage } from './pages/NewResearchPage';
import { ResearchDetailsPage } from './pages/ResearchDetailsPage';
import { HistoryPage, NotFoundPage } from './pages/HistoryPage';
import { LoginPage, RegisterPage } from './pages/AuthPages';

export function App() {
  return (
    <Routes>
      {/* Public: sign-in / registration (redirect home when already signed in). */}
      <Route
        path="/login"
        element={
          <RedirectIfSignedIn>
            <Layout />
          </RedirectIfSignedIn>
        }
      >
        <Route index element={<LoginPage />} />
      </Route>
      <Route
        path="/register"
        element={
          <RedirectIfSignedIn>
            <Layout />
          </RedirectIfSignedIn>
        }
      >
        <Route index element={<RegisterPage />} />
      </Route>
      {/* Protected: everything research-related requires a Bearer session. */}
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="research/new" element={<NewResearchPage />} />
        <Route path="research/:id" element={<ResearchDetailsPage />} />
        <Route path="history" element={<HistoryPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
