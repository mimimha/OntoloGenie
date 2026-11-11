import { apiGet, apiPost } from '../../api/client'
import { ApiError, isRecord } from '../../api/errors'

export type ReverificationRun = {
  id: number
  triggerKind: 'REVERIFY'
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  scope: { kind: 'ISSUES'; issueIds: number[]; linkMappingIds: number[] }
  comparedToRunId: number
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
  dataBasis: {
    resources: { sourceResourceId: number; lastSyncRunId: number | null }[]
  }
}
export type ReverificationAccepted = ReverificationRun & {
  joined: boolean
  reused: boolean
}

const positiveId = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const date = (value: unknown) =>
  typeof value === 'string' && Number.isFinite(Date.parse(value))

function readRun(
  value: unknown,
  issueId: number,
  previousId: number,
  id?: number,
): ReverificationRun {
  if (
    !isRecord(value) ||
    !positiveId(value.id) ||
    (id !== undefined && value.id !== id) ||
    value.id === previousId ||
    value.triggerKind !== 'REVERIFY' ||
    !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(
      String(value.status),
    ) ||
    value.comparedToRunId !== previousId ||
    !isRecord(value.scope) ||
    value.scope.kind !== 'ISSUES' ||
    !Array.isArray(value.scope.issueIds) ||
    value.scope.issueIds.length !== 1 ||
    value.scope.issueIds[0] !== issueId ||
    !Array.isArray(value.scope.linkMappingIds) ||
    value.scope.linkMappingIds.length !== 1 ||
    !value.scope.linkMappingIds.every(positiveId) ||
    !date(value.requestedAt) ||
    !(value.startedAt === null || date(value.startedAt)) ||
    !(value.finishedAt === null || date(value.finishedAt)) ||
    !(value.errorMessage === null || typeof value.errorMessage === 'string') ||
    !isRecord(value.dataBasis) ||
    !Array.isArray(value.dataBasis.resources) ||
    !value.dataBasis.resources.every(
      (resource: unknown) =>
        isRecord(resource) &&
        positiveId(resource.sourceResourceId) &&
        (resource.lastSyncRunId === null || positiveId(resource.lastSyncRunId)),
    )
  ) {
    throw new ApiError(
      '재검증 실행의 대상·비교 기준 또는 응답을 확인할 수 없습니다. 실행 이력을 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  }
  return value as ReverificationRun
}

// 060: 문제 하나를 그 문제를 발견한 성공 검사와 비교해 재검증한다.
export async function requestReverification(
  issueId: number,
  previousId: number,
): Promise<ReverificationAccepted> {
  const response = await apiPost<unknown>('/checks/runs', {
    triggerKind: 'REVERIFY',
    scope: { kind: 'ISSUES', issueIds: [issueId] },
    comparedToRunId: previousId,
  })
  const data = response?.data
  const run = readRun(data, issueId, previousId)
  if (
    !isRecord(data) ||
    typeof data.joined !== 'boolean' ||
    typeof data.reused !== 'boolean' ||
    (data.joined && data.reused) ||
    (data.reused && run.status !== 'SUCCEEDED')
  ) {
    throw new ApiError(
      '재검증 접수 결과를 확인할 수 없습니다. 실행 이력을 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  }
  return { ...run, joined: data.joined, reused: data.reused }
}

// 061: 요청한 문제·비교 실행과 일치하는 재검증 상태만 화면에 전달한다.
export async function fetchReverificationRun(
  id: number,
  issueId: number,
  previousId: number,
  signal?: AbortSignal,
) {
  const response = await apiGet<unknown>(`/checks/runs/${id}`, { signal })
  return readRun(response.data, issueId, previousId, id)
}
