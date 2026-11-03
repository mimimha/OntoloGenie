import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test } from 'vitest'
import { server } from '../../mocks/node'
import { resetSourceMocks } from '../../mocks/sourceHandlers'
import SourcesPage from './SourcesPage'
import type { ConnectionCheck } from './types'

afterEach(() => {
  resetSourceMocks()
})

function renderSourcesPage(path = '/admin/sources') {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/admin/sources" element={<SourcesPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function checkResult(overrides: Partial<ConnectionCheck>): ConnectionCheck {
  return {
    checkId: 101,
    dataSourceId: 1,
    status: 'SUCCEEDED',
    requestedBy: 1,
    requestedAt: '2026-11-03T09:00:00+09:00',
    startedAt: '2026-11-03T09:00:00+09:00',
    finishedAt: '2026-11-03T09:00:03+09:00',
    errorMessage: null,
    result: { accounts: [] },
    ...overrides,
  }
}

test('등록된 원천을 표로 보여주고 첫 원천의 접속 정보를 연다', async () => {
  renderSourcesPage()

  expect(
    screen.getByRole('heading', { name: '데이터 연결' }),
  ).toBeInTheDocument()
  const table = await screen.findByRole('table', { name: '등록된 원천 목록' })
  const rows = within(table).getAllByRole('row')
  expect(rows[1]).toHaveTextContent('ERP 운영 DB')
  expect(rows[1]).toHaveTextContent('구조화 데이터')
  expect(rows[1]).toHaveTextContent('읽기 전용 확인됨')
  expect(rows[2]).toHaveTextContent('확인 필요')
  expect(
    screen.getByRole('heading', { name: '등록된 원천' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('heading', { name: 'ERP 운영 DB 상세' }),
  ).toBeInTheDocument()

  expect(
    await screen.findByText('erp-db.internal:5432 · erp'),
  ).toBeInTheDocument()
  expect(
    screen.getByText('수집 계정 platform_ro · AI 분석 계정 platform_ai'),
  ).toBeInTheDocument()
  expect(screen.getByText('SELECT only · 검증됨')).toBeInTheDocument()
  // 가장 최근 검사의 계정별 결과
  const results = await screen.findByRole('list', { name: '계정별 결과' })
  expect(results).toHaveTextContent('수집 계정: 읽을 수 있는 표 18개 · 컬럼')
})

test('검사한 적 없는 원천은 미검증으로 보이고 결과가 없다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  await screen.findByText('erp-db.internal:5432 · erp')

  await user.click(screen.getByRole('button', { name: 'MES 운영 DB' }))

  expect(
    screen.getByRole('heading', { name: 'MES 운영 DB 상세' }),
  ).toBeInTheDocument()
  expect(await screen.findByText('미검증')).toBeInTheDocument()
  expect(
    screen.queryByRole('region', { name: '연결 확인 결과' }),
  ).not.toBeInTheDocument()
})

test('연결 다시 확인을 누르면 끝날 때까지 다시 조회하고 계정별 결과를 보여준다', async () => {
  const user = userEvent.setup()
  let calls = 0
  server.use(
    http.get('/api/v1/sources/:id/connection-checks/:checkId', () => {
      calls += 1
      return HttpResponse.json({
        data:
          calls === 1
            ? checkResult({ status: 'RUNNING', finishedAt: null })
            : checkResult({
                result: {
                  accounts: [
                    {
                      purpose: 'SYNC',
                      status: 'SUCCEEDED',
                      readableTableCount: 15,
                      readableColumnCount: 120,
                      errorMessage: null,
                    },
                  ],
                },
              }),
      })
    }),
  )
  renderSourcesPage('/admin/sources?source=2')
  await screen.findByText('미검증')

  await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))

  expect(await screen.findByText('검사 중')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '확인 중…' })).toBeDisabled()
  expect(
    await screen.findByText(
      '수집 계정: 읽을 수 있는 표 15개 · 컬럼 120개',
      {},
      { timeout: 4000 },
    ),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '연결 다시 확인' })).toBeEnabled()
})

test('계정 접속에 실패하면 계정별 실패 사유를 보여준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.get('/api/v1/sources/:id/connection-checks/:checkId', () =>
      HttpResponse.json({
        data: checkResult({
          status: 'FAILED',
          errorMessage: '일부 계정 접속에 실패했습니다.',
          result: {
            accounts: [
              {
                purpose: 'SYNC',
                status: 'SUCCEEDED',
                readableTableCount: 18,
                readableColumnCount: 140,
                errorMessage: null,
              },
              {
                purpose: 'AI',
                status: 'FAILED',
                readableTableCount: null,
                readableColumnCount: null,
                errorMessage: '원천 계정 인증에 실패했습니다',
              },
            ],
          },
        }),
      }),
    ),
  )
  renderSourcesPage('/admin/sources?source=2')
  await screen.findByText('미검증')

  await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '일부 계정 접속에 실패했습니다.',
  )
  expect(
    screen.getByText('AI 분석 계정: 실패 · 원천 계정 인증에 실패했습니다'),
  ).toBeInTheDocument()
})

test('같은 원천의 확인이 진행 중이면 합류했다고 알려준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.post('/api/v1/sources/:id/test-connection', () =>
      HttpResponse.json(
        {
          data: {
            checkId: 77,
            status: 'RUNNING',
            joined: true,
            requestedAt: '2026-11-03T09:00:00+09:00',
            startedAt: '2026-11-03T09:00:00+09:00',
            finishedAt: null,
            errorMessage: null,
          },
        },
        { status: 202 },
      ),
    ),
  )
  renderSourcesPage('/admin/sources?source=2')
  await screen.findByText('미검증')

  await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))

  expect(
    await screen.findByText(
      '이미 진행 중인 확인이 있어 그 결과를 함께 보여 줍니다.',
    ),
  ).toBeInTheDocument()
})

test('연결 확인 요청 권한이 없으면 서버가 준 안내를 보여준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.post('/api/v1/sources/:id/test-connection', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '요청을 실행할 권한이 없습니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 403 },
      ),
    ),
  )
  renderSourcesPage('/admin/sources?source=2')
  await screen.findByText('미검증')

  await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '요청을 실행할 권한이 없습니다.',
  )
})

test('수집 대상 탭에서 읽을 수 있는 표를 펼치면 컬럼과 기본키를 보여준다', async () => {
  const user = userEvent.setup()
  renderSourcesPage('/admin/sources?source=2')
  await screen.findByText('미검증')

  await user.click(screen.getByRole('button', { name: '수집 대상' }))

  expect(await screen.findByText('표 15개')).toBeInTheDocument()
  await user.click(screen.getByText('mes.work_order · 작업지시'))

  const columns = await screen.findByRole('list', {
    name: 'mes.work_order 컬럼',
  })
  const key = within(columns).getByText('wo_no').closest('li')!
  expect(key).toHaveTextContent('wo_no · varchar(20) · 기본키')
})

test('원천 목록을 불러오지 못하면 다시 불러올 수 있게 안내한다', async () => {
  server.use(
    http.get('/api/v1/sources', () =>
      HttpResponse.json(
        {
          error: {
            code: 'INTERNAL_ERROR',
            message: '일시적인 오류입니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 500 },
      ),
    ),
  )
  renderSourcesPage()

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '일시적인 오류입니다.',
  )
  expect(
    screen.getByRole('button', { name: '다시 불러오기' }),
  ).toBeInTheDocument()
})
