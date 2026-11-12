import { http, HttpResponse } from 'msw'
import type { CheckRun, ManualScope } from '../features/checks/api'
import { getMockAccount } from './auth'

export const checkRunFixture: CheckRun = {
  id: 801,
  triggerKind: 'SYNC',
  status: 'SUCCEEDED',
  scope: { kind: 'ALL', linkMappingIds: [12] },
  comparedToRunId: null,
  requestedAt: '2026-11-12T01:00:00Z',
  requestedBy: null,
  startedAt: '2026-11-12T01:00:01Z',
  finishedAt: '2026-11-12T01:00:02Z',
  errorMessage: null,
  syncRunId: null,
  dataBasis: { resources: [{ sourceResourceId: 21, lastSyncRunId: 101 }] },
}
let runs: CheckRun[] = [structuredClone(checkRunFixture)]
const polls = new Map<number, number>()
export function resetCheckMocks() {
  runs = [structuredClone(checkRunFixture)]
  polls.clear()
}
function error(status: number, message: string) {
  return HttpResponse.json(
    {
      error: {
        code: status === 403 ? 'FORBIDDEN' : 'BUSINESS_RULE_VIOLATION',
        message,
        details: {},
      },
    },
    { status },
  )
}
function denied() {
  const account = getMockAccount()
  return !account || account.roleCode !== 'ADMIN' || account.mustChangePassword
}

export const checkHandlers = [
  http.get('/api/v1/checks/runs', ({ request }) => {
    if (denied()) return error(403, '관리자만 검사 실행을 조회할 수 있습니다.')
    const search = new URL(request.url).searchParams
    const page = Number(search.get('page') ?? 0)
    const size = Number(search.get('size') ?? 20)
    const filtered = runs.filter(
      (run) =>
        (!search.get('triggerKind') ||
          run.triggerKind === search.get('triggerKind')) &&
        (!search.get('status') || run.status === search.get('status')) &&
        (!search.get('from') ||
          Date.parse(run.requestedAt) >= Date.parse(search.get('from')!)) &&
        (!search.get('to') ||
          Date.parse(run.requestedAt) <= Date.parse(search.get('to')!)),
    )
    return HttpResponse.json({
      data: filtered.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalElements: filtered.length,
        hasNext: (page + 1) * size < filtered.length,
      },
    })
  }),
  http.post('/api/v1/checks/runs', async ({ request }) => {
    if (denied()) return error(403, '관리자만 수동 검사를 실행할 수 있습니다.')
    const body = (await request.json()) as {
      triggerKind: string
      scope: ManualScope
    }
    if (
      body.triggerKind !== 'MANUAL' ||
      !['ALL', 'LINK_MAPPINGS'].includes(body.scope?.kind)
    )
      return error(422, '수동 검사 범위를 확인해 주세요.')
    const ids =
      body.scope.kind === 'ALL'
        ? [12]
        : [...body.scope.linkMappingIds].sort((a, b) => a - b)
    if (!ids.length) return error(422, '검사할 규칙이 없습니다.')
    const existing = runs.find(
      (run) =>
        run.triggerKind === 'MANUAL' &&
        run.scope.kind === body.scope.kind &&
        JSON.stringify(run.scope.linkMappingIds) === JSON.stringify(ids) &&
        run.status !== 'FAILED',
    )
    if (existing)
      return HttpResponse.json(
        {
          data: {
            ...existing,
            joined: existing.status !== 'SUCCEEDED',
            reused: existing.status === 'SUCCEEDED',
          },
        },
        { status: existing.status === 'SUCCEEDED' ? 200 : 202 },
      )
    const run: CheckRun = {
      ...structuredClone(checkRunFixture),
      id: Math.max(...runs.map((run) => run.id)) + 1,
      triggerKind: 'MANUAL',
      status: 'QUEUED',
      scope: { kind: body.scope.kind, linkMappingIds: ids },
      requestedBy: getMockAccount()!.id,
      requestedAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
    }
    runs.unshift(run)
    return HttpResponse.json(
      { data: { ...run, joined: false, reused: false } },
      { status: 202 },
    )
  }),
  http.get('/api/v1/checks/runs/:id/results', ({ params, request }) => {
    const run = runs.find((run) => run.id === Number(params.id))
    if (!run) return
    if (denied()) return error(403, '관리자만 검사 결과를 조회할 수 있습니다.')
    const search = new URL(request.url).searchParams
    const page = Number(search.get('page') ?? 0)
    const size = Number(search.get('size') ?? 20)
    const results =
      run.status === 'SUCCEEDED'
        ? (run.scope.linkMappingIds ?? [])
            .filter(
              (id) =>
                !search.get('linkMappingId') ||
                id === Number(search.get('linkMappingId')),
            )
            .map((id) => ({
              id: 900 + id,
              checkRunId: run.id,
              linkMappingId: id,
              proposalItemId: null,
              proposalItemRevision: null,
              previousResultId: null,
              totalCount: 3,
              matchedCount: 2,
              unmatchedCount: 1,
              duplicateCount: 0,
              violationCount: 0,
              matchRate: 0.6667,
              sampleRows: [
                {
                  sourceKey: { id: 'WO-102' },
                  rawValue: 'PR-99',
                  transformedValue: 'PR-99',
                  targetObjectIds: [],
                  classification: 'UNMATCHED',
                  reason: '대상 설비가 없습니다.',
                },
              ],
            }))
        : []
    return HttpResponse.json({
      data: results.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalElements: results.length,
        hasNext: (page + 1) * size < results.length,
      },
    })
  }),
  http.get('/api/v1/checks/runs/:id', ({ params }) => {
    if (denied()) return error(403, '관리자만 검사 상태를 조회할 수 있습니다.')
    const run = runs.find((run) => run.id === Number(params.id))
    if (!run) return error(404, '검사 실행을 찾을 수 없습니다.')
    if (run.status === 'QUEUED' || run.status === 'RUNNING') {
      const count = (polls.get(run.id) ?? 0) + 1
      polls.set(run.id, count)
      run.status = count === 1 ? 'RUNNING' : 'SUCCEEDED'
      run.startedAt ??= new Date().toISOString()
      if (run.status === 'SUCCEEDED') run.finishedAt = new Date().toISOString()
    }
    return HttpResponse.json({ data: run })
  }),
]
