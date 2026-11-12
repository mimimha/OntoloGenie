import { http, HttpResponse } from 'msw'
import { beforeEach, expect, test } from 'vitest'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { checkRunFixture } from '../../mocks/checkHandlers'
import { server } from '../../mocks/node'
import { fetchRunResults } from '../review/checkApi'
import { fetchCheckRun, fetchCheckRuns, requestManualCheck } from './api'

beforeEach(() => setMockAccount({ ...demoAccount, roleCode: 'ADMIN' }))

const accepted = {
  ...checkRunFixture,
  triggerKind: 'MANUAL',
  joined: false,
  reused: true,
}

test.each([
  { ...accepted, triggerKind: 'CANDIDATE' },
  { ...accepted, scope: { kind: 'LINK_MAPPINGS', linkMappingIds: [12] } },
  { ...accepted, scope: { kind: 'ALL', linkMappingIds: [] } },
  { ...accepted, joined: true, reused: true },
  { ...accepted, status: 'RUNNING' },
  { ...accepted, comparedToRunId: 7 },
])(
  '다른 종류·범위·모순된 접수 응답은 완료로 처리하지 않는다: %j',
  async (data) => {
    server.use(
      http.post('/api/v1/checks/runs', () => HttpResponse.json({ data })),
    )
    await expect(requestManualCheck({ kind: 'ALL' })).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  },
)

test('선택한 규칙과 다른 개정본의 접수 응답을 거절한다', async () => {
  server.use(
    http.post('/api/v1/checks/runs', () =>
      HttpResponse.json({
        data: {
          ...accepted,
          scope: { kind: 'LINK_MAPPINGS', linkMappingIds: [13] },
        },
      }),
    ),
  )
  await expect(
    requestManualCheck({ kind: 'LINK_MAPPINGS', linkMappingIds: [12] }),
  ).rejects.toMatchObject({ kind: 'invalid-response' })
})

test('합류 직후 완료된 실행도 유효한 접수 응답이다', async () => {
  server.use(
    http.post('/api/v1/checks/runs', () =>
      HttpResponse.json(
        { data: { ...accepted, joined: true, reused: false } },
        { status: 202 },
      ),
    ),
  )
  await expect(requestManualCheck({ kind: 'ALL' })).resolves.toMatchObject({
    joined: true,
    status: 'SUCCEEDED',
  })
})

test('059는 페이지 정보가 없거나 요청 페이지와 다르면 실패한다', async () => {
  server.use(
    http.get('/api/v1/checks/runs', () => HttpResponse.json({ data: [] })),
  )
  await expect(fetchCheckRuns({}, 0)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
  server.use(
    http.get('/api/v1/checks/runs', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      }),
    ),
  )
  await expect(fetchCheckRuns({}, 1)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
})

test('061의 존재하지 않는 실행은 404로 구분한다', async () => {
  await expect(fetchCheckRun(999)).rejects.toMatchObject({
    kind: 'not-found',
    status: 404,
  })
})

test('062는 제안·규칙 필터 없이 실행 전체 결과를 페이지로 조회하고 실행 ID를 검증한다', async () => {
  server.use(
    http.get('/api/v1/checks/runs/:id/results', ({ request }) => {
      const params = new URL(request.url).searchParams
      expect(params.get('page')).toBe('1')
      expect(params.get('size')).toBe('20')
      expect(params.has('proposalItemId')).toBe(false)
      expect(params.has('linkMappingId')).toBe(false)
      return HttpResponse.json({
        data: [{ checkRunId: 999 }],
        meta: { page: 1, size: 20, totalElements: 21, hasNext: false },
      })
    }),
  )
  await expect(fetchRunResults(801, undefined, 1)).rejects.toMatchObject({
    kind: 'conflict',
  })
})
