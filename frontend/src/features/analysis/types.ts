export type AnalysisStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export type AnalysisTarget = {
  sourceResourceId: number
  // 고른 컬럼. 없으면 그 표 전체
  columns?: string[]
}

// 018 분석 실행 접수 응답
export type AnalysisRun = {
  id: number
  runKind: 'INITIAL' | 'REANALYSIS'
  status: AnalysisStatus
  targets: AnalysisTarget[]
  requestedAt: string
  // 같은 대상의 실행에 합류했는지
  joined: boolean
}
