import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import SourceSyncRuns from './SourceSyncRuns'
import { sourceSyncApi, type SyncRun, type SyncAccepted } from './syncApi'

const resource = {
  syncRunId: 21,
  dataSourceId: 1,
  sourceResourceId: 10,
  syncStrategy: 'CDC' as const,
  mode: 'FULL' as const,
  status: 'SUCCEEDED' as const,
  fromLsn: null,
  toLsn: 100,
  rowsRead: 12,
  rowsUpserted: 10,
  rowsDeleted: 2,
  startedAt: null,
  finishedAt: null,
  errorMessage: null,
}
function run(overrides: Partial<SyncRun> = {}): SyncRun {
  return {
    id: 21,
    syncRunId: 21,
    dataSourceId: 1,
    triggerKind: 'MANUAL',
    status: 'SUCCEEDED',
    requestedBy: 1,
    requestedAt: '2026-11-07T01:00:00Z',
    startedAt: null,
    finishedAt: null,
    errorMessage: null,
    resources: [resource],
    ...overrides,
  }
}
function accepted(overrides: Partial<SyncAccepted> = {}): SyncAccepted {
  return {
    syncRunId: 21,
    dataSourceId: 1,
    triggerKind: 'MANUAL',
    status: 'QUEUED',
    requestedBy: 1,
    requestedAt: '2026-11-07T01:00:00Z',
    joinedExisting: false,
    resources: [{ ...resource, joined: false }],
    ...overrides,
  }
}
function mocks() {
  server.use(
    http.get('/api/v1/sources/1/resources', () =>
      HttpResponse.json({
        data: [
          {
            id: 10,
            dataSourceId: 1,
            resourceKey: 'public.orders',
            resourceKind: 'TABLE',
            isReadable: true,
            sourceMetadata: {},
          },
        ],
      }),
    ),
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json({
        data: [run()],
        meta: {
          page: 0,
          size: 20,
          totalElements: 1,
          hasNext: false,
          cdcLastAppliedAt: null,
        },
      }),
    ),
    http.get('/api/v1/sync-runs/:id', ({ params }) =>
      HttpResponse.json({
        data: run({ id: Number(params.id), syncRunId: Number(params.id) }),
      }),
    ),
    http.post('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json({ data: accepted() }, { status: 202 }),
    ),
  )
}
function open(api = true) {
  mocks()
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <SourceSyncRuns sourceId={1} api={api ? sourceSyncApi : undefined} />
    </QueryClientProvider>,
  )
}

test('API 미제공 상태에서는 요청과 조회를 실행하지 않는다', async () => {
  const post = vi.fn()
  open(false)
  server.use(http.post('/api/v1/sources/1/sync-runs', post))
  expect(screen.getByRole('button', { name: '수동 수집 요청' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '이력 새로고침' })).toBeDisabled()
  expect(
    screen.getByText('수동 수집과 실행 이력 조회는 아직 사용할 수 없습니다.'),
  ).toBeInTheDocument()
  expect(post).not.toHaveBeenCalled()
})

test('전체 요청은 테이블 ID를 생략하고 연속 클릭은 한 번만 접수한다', async () => {
  const user = userEvent.setup()
  open()
  const post = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({})
    await delay(100)
    return HttpResponse.json({ data: accepted() }, { status: 202 })
  })
  server.use(http.post('/api/v1/sources/1/sync-runs', post))
  await user.dblClick(screen.getByRole('button', { name: '수동 수집 요청' }))
  await screen.findByText('수집 실행 #21을 접수했습니다.')
  expect(post).toHaveBeenCalledTimes(1)
  const detail = within(
    await screen.findByRole('region', { name: '수집 실행 #21 상세' }),
  )
  expect(
    detail.getByText('읽음 12 · 생성·갱신 10 · 삭제 2'),
  ).toBeInTheDocument()
})

test('선택 요청은 필수 선택을 검사하고 테이블별 기존 실행 여러 건을 안내한다', async () => {
  const user = userEvent.setup()
  open()
  server.use(
    http.post('/api/v1/sources/1/sync-runs', async ({ request }) => {
      expect(await request.json()).toEqual({ sourceResourceIds: [10] })
      return HttpResponse.json(
        {
          data: accepted({
            syncRunId: null,
            status: null,
            joinedExisting: true,
            resources: [
              { ...resource, syncRunId: 31, joined: true },
              {
                ...resource,
                sourceResourceId: 11,
                syncRunId: 32,
                joined: true,
              },
            ],
          }),
        },
        { status: 202 },
      )
    }),
  )
  await user.click(
    screen.getByRole('checkbox', { name: '활성 매핑이 있는 모든 테이블 수집' }),
  )
  expect(screen.getByRole('button', { name: '수동 수집 요청' })).toBeDisabled()
  await user.click(
    await screen.findByRole('checkbox', { name: 'public.orders' }),
  )
  await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
  await screen.findByText('새 실행 없이 기존 수집에 합류했습니다.')
  expect(
    screen.getByRole('button', { name: '실행 #31 보기' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '실행 #32 보기' }))
  await screen.findByRole('region', { name: '수집 실행 #32 상세' })
})

test.each([
  [403, 'FORBIDDEN'],
  [409, 'CONFLICT'],
  [422, 'VALIDATION_ERROR'],
  [404, 'NOT_FOUND'],
])(
  '요청 실패 %s는 오류를 알리고 자동으로 재접수하지 않는다',
  async (status, code) => {
    const user = userEvent.setup()
    open()
    const post = vi.fn(() =>
      HttpResponse.json(
        {
          error: {
            code,
            message: '수집 요청을 처리할 수 없습니다.',
            details: {},
            requestId: 'test-120',
          },
        },
        { status },
      ),
    )
    server.use(http.post('/api/v1/sources/1/sync-runs', post))
    await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
    await screen.findByText('수집 요청을 처리할 수 없습니다.')
    expect(post).toHaveBeenCalledTimes(1)
    expect(
      screen.queryByText('수집 실행 #21을 접수했습니다.'),
    ).not.toBeInTheDocument()
  },
)

test('상태·CDC·기간 필터와 페이지 조건을 서버로 보내고 잘못된 기간은 막는다', async () => {
  const user = userEvent.setup()
  open()
  const queries: string[] = []
  server.use(
    http.get('/api/v1/sources/1/sync-runs', ({ request }) => {
      queries.push(request.url)
      return HttpResponse.json({
        data: [],
        meta: {
          page: 0,
          size: 20,
          totalElements: 21,
          hasNext: true,
          cdcLastAppliedAt: null,
        },
      })
    }),
  )
  await user.selectOptions(screen.getByLabelText('실행 상태'), 'FAILED')
  await user.selectOptions(screen.getByLabelText('시작 원인'), 'CDC')
  await waitFor(() =>
    expect(
      queries.some(
        (url) =>
          url.includes('status=FAILED') && url.includes('triggerKind=CDC'),
      ),
    ).toBe(true),
  )
  const from = screen.getByLabelText('접수 시작(포함)')
  const to = screen.getByLabelText('접수 종료(미포함)')
  // datetime-local의 브라우저 입력 이벤트를 사용한다.
  const { fireEvent } = await import('@testing-library/react')
  fireEvent.change(from, { target: { value: '2026-11-07T12:00' } })
  fireEvent.change(to, { target: { value: '2026-11-07T11:00' } })
  expect(screen.getByRole('button', { name: '기간 적용' })).toBeDisabled()
  fireEvent.change(to, { target: { value: '2026-11-07T13:00' } })
  await user.click(screen.getByRole('button', { name: '기간 적용' }))
  await waitFor(() =>
    expect(queries.some((url) => new URL(url).searchParams.has('from'))).toBe(
      true,
    ),
  )
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await waitFor(() =>
    expect(
      queries.some((url) => new URL(url).searchParams.get('page') === '1'),
    ).toBe(true),
  )
})

test('실행 중 상세는 완료까지 갱신하고 실패 사유와 테이블 처리 건수를 보여준다', async () => {
  const user = userEvent.setup()
  open()
  let calls = 0
  server.use(
    http.get('/api/v1/sync-runs/21', () =>
      HttpResponse.json({
        data: run(
          ++calls === 1
            ? { status: 'RUNNING' }
            : {
                status: 'FAILED',
                errorMessage: 'heartbeat 시간 초과',
                resources: [
                  {
                    ...resource,
                    status: 'FAILED',
                    errorMessage: '테이블 수집 중단',
                  },
                ],
              },
        ),
      }),
    ),
  )
  await user.click(await screen.findByRole('button', { name: '수집 실행 #21' }))
  const detail = within(
    await screen.findByRole('region', { name: '수집 실행 #21 상세' }),
  )
  await detail.findByText('heartbeat 시간 초과')
  expect(detail.getByText('테이블 수집 중단')).toBeInTheDocument()
  expect(calls).toBe(2)
})

test('이력 조회 실패는 빈 목록으로 숨기지 않고 재조회한다', async () => {
  const user = userEvent.setup()
  open()
  server.use(
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json(
        {
          error: {
            code: 'INTERNAL_ERROR',
            message: '이력 조회 실패',
            details: {},
            requestId: 'test',
          },
        },
        { status: 500 },
      ),
    ),
  )
  await screen.findByText('이력 조회 실패')
  expect(
    screen.queryByText('수집 실행 이력이 없습니다.'),
  ).not.toBeInTheDocument()
  server.use(
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json({ data: [] }),
    ),
  )
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  await screen.findByText('수집 실행 이력이 없습니다.')
})
