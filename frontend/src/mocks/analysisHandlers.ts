import { delay, http, HttpResponse } from 'msw'
import type { AnalysisRun, AnalysisTarget } from '../features/analysis/types'

let runs: AnalysisRun[] = []

export function resetAnalysisMocks() {
  runs = []
}

export const analysisHandlers = [
  // 018 선택 범위 AI 분석 실행. 같은 대상이 대기·실행 중이면 그 실행에 합류한다.
  http.post('/api/v1/analyses', async ({ request }) => {
    await delay(150)
    const body = (await request.json()) as { targets: AnalysisTarget[] }
    const key = JSON.stringify(body.targets)
    const running = runs.find((run) => JSON.stringify(run.targets) === key)
    if (running) {
      return HttpResponse.json(
        { data: { ...running, joined: true } },
        { status: 202 },
      )
    }
    const run: AnalysisRun = {
      id: runs.length + 1,
      runKind: 'INITIAL',
      status: 'QUEUED',
      targets: body.targets,
      requestedAt: new Date().toISOString(),
      joined: false,
    }
    runs.push(run)
    return HttpResponse.json({ data: run }, { status: 202 })
  }),
]
