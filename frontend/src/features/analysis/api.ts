import { apiPost, ApiError } from '../../api/client'
import type { AnalysisRun, AnalysisTarget } from './types'

// 018 선택 범위 AI 분석 실행
export async function startAnalysis(targets: AnalysisTarget[]) {
  const body = await apiPost<AnalysisRun>('/analyses', {
    runKind: 'INITIAL',
    targets,
  })
  if (!body?.data || !Number.isSafeInteger(body.data.id)) {
    throw new ApiError('분석 접수 응답을 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  }
  return body.data
}
