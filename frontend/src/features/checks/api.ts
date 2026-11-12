import { apiGet, apiPost } from '../../api/client'
import { ApiError, isRecord } from '../../api/errors'

export const CHECK_RUN_PAGE_SIZE = 20
export const runStatuses = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
} as const
export const runTriggers = {
  MANUAL: '수동 검사',
  SYNC: '자동 검사',
  REVERIFY: '문제 재검증',
  CANDIDATE: '승인 전 후보 시험',
} as const

export type CheckRun = {
  id: number
  triggerKind: keyof typeof runTriggers
  status: keyof typeof runStatuses
  scope: Record<string, unknown> & { linkMappingIds?: number[] }
  comparedToRunId: number | null
  requestedAt: string
  requestedBy: number | null
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
  syncRunId: number | null
  dataBasis: {
    resources: { sourceResourceId: number; lastSyncRunId: number | null }[]
  }
}
export type CheckRunAccepted = CheckRun & { joined: boolean; reused: boolean }
export type ManualScope =
  { kind: 'ALL' } | { kind: 'LINK_MAPPINGS'; linkMappingIds: number[] }
export type CheckRunFilters = {
  triggerKind?: CheckRun['triggerKind']
  status?: CheckRun['status']
  from?: string
  to?: string
}

export const activeRun = (status?: string) =>
  status === 'QUEUED' || status === 'RUNNING'
const positiveId = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const timestamp = (value: unknown) =>
  typeof value === 'string' && Number.isFinite(Date.parse(value))
const nullableId = (value: unknown) => value === null || positiveId(value)

function readRun(value: unknown, id?: number): CheckRun {
  if (
    !isRecord(value) ||
    !positiveId(value.id) ||
    (id !== undefined && value.id !== id) ||
    !Object.hasOwn(runStatuses, String(value.status)) ||
    !Object.hasOwn(runTriggers, String(value.triggerKind)) ||
    !isRecord(value.scope) ||
    (value.scope.linkMappingIds !== undefined &&
      (!Array.isArray(value.scope.linkMappingIds) ||
        !value.scope.linkMappingIds.every(positiveId))) ||
    !nullableId(value.comparedToRunId) ||
    !nullableId(value.requestedBy) ||
    !nullableId(value.syncRunId) ||
    !timestamp(value.requestedAt) ||
    !(value.startedAt === null || timestamp(value.startedAt)) ||
    !(value.finishedAt === null || timestamp(value.finishedAt)) ||
    !(value.errorMessage === null || typeof value.errorMessage === 'string') ||
    !isRecord(value.dataBasis) ||
    !Array.isArray(value.dataBasis.resources) ||
    !value.dataBasis.resources.every(
      (resource: unknown) =>
        isRecord(resource) &&
        positiveId(resource.sourceResourceId) &&
        nullableId(resource.lastSyncRunId),
    )
  ) {
    throw new ApiError('검사 실행 응답을 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  }
  return value as CheckRun
}

// 059: 접수 시각 내림차순의 검사 실행 목록.
export async function fetchCheckRuns(
  filters: CheckRunFilters,
  page: number,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(CHECK_RUN_PAGE_SIZE),
  })
  for (const [key, value] of Object.entries(filters))
    if (value) params.set(key, value)
  const response = await apiGet<unknown[]>(`/checks/runs?${params}`, { signal })
  if (
    !response.meta ||
    response.meta.page !== page ||
    !Array.isArray(response.data)
  )
    throw new ApiError('검사 실행 목록의 페이지 정보를 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  return { data: response.data.map((row) => readRun(row)), meta: response.meta }
}

// 061: 선택한 실행의 상태를 조회한다.
export async function fetchCheckRun(id: number, signal?: AbortSignal) {
  const response = await apiGet<unknown>(`/checks/runs/${id}`, { signal })
  return readRun(response.data, id)
}

// 060: 승인 전 후보가 아닌 반영 중인 규칙을 검사한다.
export async function requestManualCheck(
  scope: ManualScope,
): Promise<CheckRunAccepted> {
  const response = await apiPost<unknown>('/checks/runs', {
    triggerKind: 'MANUAL',
    scope,
  })
  const value = response?.data
  const run = readRun(value)
  const ids = run.scope.linkMappingIds
  if (
    !isRecord(value) ||
    run.triggerKind !== 'MANUAL' ||
    run.comparedToRunId !== null ||
    run.scope.kind !== scope.kind ||
    !ids?.length ||
    (scope.kind === 'LINK_MAPPINGS' &&
      (ids.length !== scope.linkMappingIds.length ||
        !scope.linkMappingIds.every((id) => ids.includes(id)))) ||
    typeof value.joined !== 'boolean' ||
    typeof value.reused !== 'boolean' ||
    (value.joined && value.reused) ||
    (value.reused && run.status !== 'SUCCEEDED')
  )
    throw new ApiError(
      '검사 접수 결과의 대상·상태를 확인할 수 없습니다. 실행 목록을 확인해 주세요.',
      {
        kind: 'invalid-response',
      },
    )
  return { ...run, joined: value.joined, reused: value.reused }
}
