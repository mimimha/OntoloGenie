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

// 080·096 실행 하나가 바꾼 행의 전후 내용
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
  input: Record<string, unknown>
  status: ExecutionStatus
  blockedReason?: string | null
  result: Record<string, unknown>
  requestedAt: string
  finishedAt?: string | null
  entityChanges: EntityChange[]
  // 권한으로 가린 속성 키. 이 목록에 있는 키의 null은 "권한으로 가림", 없으면 "값 없음"이다.
  restrictedPropertyCodes?: string[]
}
