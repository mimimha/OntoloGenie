import type { CheckStatus } from './types'

// 화면 전용 모델. 016·102 계약과 가림 정책 확정 후 어댑터에서 변환한다.
// 가린 셀에는 원문을 전달하지 않는다.
export type SampleCell =
  | { kind: 'visible'; value: string | number | boolean | null }
  | { kind: 'masked' }
  | { kind: 'unavailable' }

export type SampleReceipt = {
  previewId: number
  joined: boolean
  expiresAt: string
}

export type SamplePreview = {
  status: CheckStatus
  columns: string[]
  rows: SampleCell[][]
}

export type SourceSampleAccess = {
  policyDescription: string
  request: (
    sourceId: number,
    resourceId: number,
    rowLimit: number,
  ) => Promise<SampleReceipt>
  read: (previewId: number) => Promise<SamplePreview>
}
