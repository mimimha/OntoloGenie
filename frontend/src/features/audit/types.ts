export type ExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'BLOCKED' | 'FAILED'

// 080·104 실행 하나가 바꾼 행의 전후 내용
export type EntityChange = {
  tableName: string
  rowKey: Record<string, unknown>
  changeKind: 'INSERT' | 'UPDATE' | 'DELETE'
  beforeData?: Record<string, unknown> | null
  afterData?: Record<string, unknown> | null
}

// 080 객체 관련 실행 이력·104 내 실행 이력 응답의 실행 하나 (096과 같은 로그 구조)
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
  actionDefinitionId?: number | null
  actorType: 'USER' | 'AGENT' | 'SYSTEM'
  actorId?: number | null
  actorRoleCode?: string | null
  checkedPermissionCode?: string | null
  entryPoint: 'WEB' | 'AGENT' | 'BATCH' | 'SYSTEM' | 'INTERNAL'
  targetType?: string | null
  targetId?: number | null
  input: Record<string, unknown>
  status: ExecutionStatus
  blockedReason?: string | null
  result: Record<string, unknown>
  requestedAt: string
  finishedAt?: string | null
  entityChanges: EntityChange[]
}
