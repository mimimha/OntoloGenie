import type { Analysis, AnalysisStatus } from './types'

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

// 목업의 분석 실행 번호 표기 (예: ANALYSIS-002)
export function analysisCode(id: number) {
  return `ANALYSIS-${String(id).padStart(3, '0')}`
}

export const analysisStatusLabels: Record<
  AnalysisStatus,
  { label: string; tone: Tone }
> = {
  QUEUED: { label: '대기 중', tone: 'info' },
  RUNNING: { label: '분석 중', tone: 'info' },
  SUCCEEDED: { label: '완료', tone: 'success' },
  FAILED: { label: '실패', tone: 'danger' },
}

// 서버가 새 상태값을 보내도 화면이 깨지지 않게 모르는 값은 받은 그대로 보여 준다
export function analysisStatusLabel(status: string): {
  label: string
  tone: Tone
} {
  return (
    analysisStatusLabels[status as AnalysisStatus] ?? {
      label: status,
      tone: 'neutral',
    }
  )
}

export function isAnalysisRunning(status: AnalysisStatus) {
  return status === 'QUEUED' || status === 'RUNNING'
}

// 분석을 맡긴 뒤 진행 상태와 후보를 보는 연결 규칙 화면 주소
export function analysisRunPath(run: { id: number; joined?: boolean }) {
  return `/admin/rules?analysis=${run.id}${run.joined ? '&joined=1' : ''}`
}

// 분석은 끝났지만 고른 범위와 다르게 분석한 부분 (resultSummary, ai/docs/api-internal.md 4절)
export function summaryNotices(run: Analysis) {
  const summary = run.resultSummary ?? {}
  const notices: string[] = []
  for (const [table, columns] of Object.entries(summary.missingColumns ?? {}))
    if (columns.length)
      notices.push(
        `${table}: 고른 컬럼 ${columns.join(', ')}이(가) 원천에서 사라져 빼고 분석했습니다.`,
      )
  for (const [table, rows] of Object.entries(summary.sampledTables ?? {}))
    notices.push(
      `${table}: 행이 많아 앞에서 ${rows.toLocaleString('ko-KR')}행만 분석했습니다.`,
    )
  return notices
}
