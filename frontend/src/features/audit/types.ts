import type { PageMeta } from '../../api/types'

export type ExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'BLOCKED' | 'FAILED'

// 실행 이력 종류 탭 (096 category). DATA_JOB은 수집·검사 같은 데이터 작업이라 전체 탭에서만 보인다.
export type ExecutionCategory = 'ANALYSIS' | 'OPERATION' | 'CHANGE' | 'DATA_JOB'

// 096이 이번 조회를 전체(ALL)로 했는지 본인 실행(MINE)으로 했는지
export type ExecutionScope = 'ALL' | 'MINE'

// 080·096 응답의 meta. scope는 096에만 온다.
export type ExecutionPageMeta = PageMeta & {
  scope?: ExecutionScope
}

// 098 실행 하나가 바꾼 행의 전후 내용
export type EntityChange = {
  tableName: string
  rowKey: Record<string, unknown>
  changeKind: 'INSERT' | 'UPDATE' | 'DELETE'
  beforeData?: Record<string, unknown> | null
  afterData?: Record<string, unknown> | null
}

// 080 객체 관련 실행 이력·096 실행·감사 이력 응답의 실행 하나
export type Execution = {
  id: number
  requestId: string
  eventKind:
    | 'ACTION'
    | 'SYNC_RUN'
    | 'CHECK_RUN'
    | 'ANALYSIS_RUN'
    | 'SOURCE_CHECK'
    | 'SOURCE_PREVIEW'
    | 'HOLD_AUTO_RESOLVE'
  category?: ExecutionCategory
  actionDefinitionId?: number | null
  actionCode?: string | null
  actionName?: string | null
  actorType: 'USER' | 'AGENT' | 'SYSTEM'
  actorId?: number | null
  // SYSTEM이면 null
  actorName?: string | null
  actorRoleCode?: string | null
  checkedPermissionCode?: string | null
  entryPoint: 'WEB' | 'AGENT' | 'BATCH' | 'SYSTEM' | 'INTERNAL'
  targetType?: string | null
  targetId?: number | null
  // 이름이 없는 대상(예: sync_run)은 null
  targetLabel?: string | null
  status: ExecutionStatus
  requestedAt: string
  finishedAt?: string | null
  changeCount: number
}

// 097 내용 보기. 표시 이름과 시각은 목록 행의 값을 사용한다.
export type ExecutionDetail = {
  id: number
  status: ExecutionStatus
  input: Record<string, unknown> | null
  result: Record<string, unknown> | null
  blockedReason: string | null
  failureReason: string | null
  // 097은 입력값, 098은 변경 내역에서 가린 속성 코드다.
  restrictedPropertyCodes: string[]
}

export type ExecutionChanges = {
  changes: EntityChange[]
  restrictedPropertyCodes: string[]
}
