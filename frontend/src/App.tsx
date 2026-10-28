import { BrowserRouter, Route, Routes } from 'react-router'
import AppLayout from './components/AppLayout'
import AgentPage from './features/agent/AgentPage'
import ExplorerPage from './features/explorer/ExplorerPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<ExplorerPage />} />
          <Route path="/agent" element={<AgentPage />} />
          <Route path="*" element={<p>페이지를 찾을 수 없어요.</p>} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
