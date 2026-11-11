import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, useNavigate } from 'react-router'
import { expect, test, vi } from 'vitest'
import { issueFixture } from '../../mocks/issueHandlers'
import { server } from '../../mocks/node'
import ProposalsPage from '../review/ProposalsPage'

test.each(['linkMappingId', 'proposalItemId'] as const)(
  '문제의 %s로 두 검사 실행의 결과와 근거를 비교한다',
  async (filter) => {
    const scope = { linkMappingId: null, proposalItemId: null, [filter]: 12 }
    const results = vi.fn(
      ({
        params,
        request,
      }: {
        params: { runId?: string }
        request: Request
      }) => {
        const runId = Number(params.runId)
        expect(new URL(request.url).searchParams.get(filter)).toBe('12')
        return HttpResponse.json({
          data: [
            {
              id: runId,
              checkRunId: runId,
              ...scope,
              totalCount: 1,
              matchedCount: runId === 2 ? 1 : 0,
              unmatchedCount: runId === 2 ? 0 : 1,
              duplicateCount: 0,
              violationCount: 0,
              matchRate: runId === 2 ? 1 : 0,
              sampleRows: [{ rawValue: runId === 2 ? '수정된 값' : '최초 값' }],
            },
          ],
          meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
        })
      },
    )
    server.use(
      http.get('/api/v1/issues/1', () =>
        HttpResponse.json({
          data: {
            ...issueFixture,
            ...scope,
            status: 'RESOLVED',
            resolvedCheckRunId: 2,
          },
        }),
      ),
      http.get('/api/v1/checks/runs/:runId/results', results),
    )
    const user = userEvent.setup()
    open('?view=issues&issue=1')
    const dialog = within(await screen.findByRole('dialog'))
    const toggle = await dialog.findByText('검사 결과 비교')
    expect(results).not.toHaveBeenCalled()
    await user.click(toggle)
    expect(
      await within(
        await dialog.findByRole('region', { name: '발견 당시 검사 결과' }),
      ).findByText('최초 값'),
    ).toBeInTheDocument()
    expect(
      await within(
        await dialog.findByRole('region', {
          name: '해결 처리에 사용한 재검증 결과',
        }),
      ).findByText('수정된 값'),
    ).toBeInTheDocument()
    expect(results).toHaveBeenCalledTimes(2)
  },
)

function HistoryControl() {
  const navigate = useNavigate()
  return (
    <button type="button" onClick={() => navigate(-1)}>
      브라우저 뒤로 가기
    </button>
  )
}

function open(
  search = '',
  previousSearch?: string,
  client = new QueryClient(),
) {
  const entries = [`/admin/rules${search}`]
  if (previousSearch !== undefined)
    entries.unshift(`/admin/rules${previousSearch}`)
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={entries}>
        <HistoryControl />
        <ProposalsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('연결 규칙에서 문제를 열어 별도 상세 응답의 원천 근거와 원인 미확정을 표시한다', async () => {
  const detail = vi.fn(() =>
    HttpResponse.json({
      data: { ...issueFixture, rawValue: '57', transformedValue: 'GP0057' },
    }),
  )
  server.use(http.get('/api/v1/issues/1', detail))
  const user = userEvent.setup()
  open()
  await user.click(screen.getByRole('link', { name: '적용 후 문제' }))
  const table = await screen.findByRole('table', { name: '정합성 문제 목록' })
  expect(within(table).getByText('W2E (연결 매핑 #12)')).toBeInTheDocument()
  await user.click(within(table).getByRole('button', { name: '#1 · 미연결' }))
  const dialog = within(await screen.findByRole('dialog'))
  expect(await dialog.findByText('GP0057')).toBeInTheDocument()
  expect(dialog.getByText('57')).toBeInTheDocument()
  expect(dialog.getByText('대응 직원 없음')).toBeInTheDocument()
  expect(dialog.getByText('MES')).toBeInTheDocument()
  expect(dialog.getByText('원인 미확정')).toBeInTheDocument()
  expect(detail).toHaveBeenCalledTimes(1)
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '브라우저 뒤로 가기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(
    screen.getByRole('heading', { name: '연결 규칙 목록' }),
  ).toBeInTheDocument()
})

test('지연된 상태 변경 응답이 더 최신 상세를 덮어쓰지 않는다', async () => {
  const client = new QueryClient()
  let resolveResponse!: (response: Response) => void
  const response = new Promise<Response>((resolve) => {
    resolveResponse = resolve
  })
  const change = vi.fn(() => response)
  server.use(http.patch('/api/v1/issues/1/status', change))
  const user = userEvent.setup()
  open('?view=issues&issue=1', undefined, client)
  const dialog = within(await screen.findByRole('dialog'))
  await user.selectOptions(
    await dialog.findByLabelText('변경할 상태'),
    'CHECKING',
  )
  await user.type(dialog.getByLabelText('변경 사유'), '확인 시작')
  await user.click(dialog.getByRole('button', { name: '상태 변경' }))
  expect(change).toHaveBeenCalledTimes(1)
  act(() => {
    client.setQueryData(['issues', 'detail', 1], {
      data: {
        ...issueFixture,
        status: 'FIXED_PENDING_REVERIFY',
        rowVersion: 2,
      },
    })
  })
  resolveResponse(
    HttpResponse.json({
      data: { ...issueFixture, status: 'CHECKING', rowVersion: 1 },
    }),
  )
  expect(
    await dialog.findByText('처리 상태를 변경했습니다.'),
  ).toBeInTheDocument()
  expect(dialog.getByText('조치완료·재검증대기')).toBeInTheDocument()
})

test('상세 URL로 직접 들어오면 닫아도 목록 필터가 유지되고 뒤로 가기가 상세를 다시 열지 않는다', async () => {
  const user = userEvent.setup()
  open('?view=issues&issueStatus=NEW&issue=1', '')
  const dialog = within(await screen.findByRole('dialog'))
  await user.click(dialog.getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByLabelText('처리 상태')).toHaveValue('NEW')

  await user.click(screen.getByRole('button', { name: '브라우저 뒤로 가기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(
    screen.getByRole('heading', { name: '연결 규칙 목록' }),
  ).toBeInTheDocument()
})

test('상태 필터를 서버에 보내고 페이지를 첫 쪽으로 되돌린다', async () => {
  const requests: URLSearchParams[] = []
  server.use(
    http.get('/api/v1/issues', ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params)
      return HttpResponse.json({ data: [] })
    }),
  )
  const user = userEvent.setup()
  open('?view=issues&issuePage=2&issueKind=UNMATCHED')
  await screen.findByText('조건에 맞는 정합성 문제가 없습니다.')
  await user.selectOptions(screen.getByLabelText('처리 상태'), 'CHECKING')
  await screen.findByText('조건에 맞는 정합성 문제가 없습니다.')
  expect(
    requests
      .filter((params) => params.has('issueKind'))
      .map((params) => [
        params.get('page'),
        params.get('status'),
        params.get('issueKind'),
      ]),
  ).toEqual([
    ['2', null, 'UNMATCHED'],
    ['0', 'CHECKING', 'UNMATCHED'],
  ])
})

test('문제 상세의 권한 오류를 표시하고 재시도 버튼을 숨긴다', async () => {
  server.use(
    http.get('/api/v1/issues/1', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '관리자 권한이 필요합니다.',
            details: {},
          },
        },
        { status: 403 },
      ),
    ),
  )
  open('?view=issues&issue=1')
  const dialog = within(await screen.findByRole('dialog'))
  expect(await dialog.findByRole('alert')).toHaveTextContent(
    '관리자 권한이 필요합니다.',
  )
  expect(
    dialog.queryByRole('button', { name: '다시 불러오기' }),
  ).not.toBeInTheDocument()
})

test('해결 요청에 최신 버전과 재검증 ID를 보내고 상세와 목록을 갱신한다', async () => {
  let issue = {
    ...issueFixture,
    status: 'FIXED_PENDING_REVERIFY',
    rowVersion: 2,
  }
  const change = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({
      status: 'RESOLVED',
      reason: '재검증 통과',
      resolvedCheckRunId: 12,
      rowVersion: 2,
    })
    issue = { ...issue, status: 'RESOLVED', rowVersion: 3 }
    return HttpResponse.json({ data: issue })
  })
  server.use(
    http.get('/api/v1/issues', () => HttpResponse.json({ data: [issue] })),
    http.get('/api/v1/issues/1', () => HttpResponse.json({ data: issue })),
    http.patch('/api/v1/issues/1/status', change),
  )
  const user = userEvent.setup()
  open('?view=issues&issue=1')
  const dialog = within(await screen.findByRole('dialog'))
  await user.selectOptions(
    await dialog.findByLabelText('변경할 상태'),
    'RESOLVED',
  )
  await user.type(dialog.getByLabelText('변경 사유'), '재검증 통과')
  const submit = dialog.getByRole('button', { name: '상태 변경' })
  expect(submit).toBeDisabled()
  await user.type(dialog.getByLabelText('통과한 재검증 실행 ID'), '12')
  await user.dblClick(submit)
  expect(
    await dialog.findByText('처리 상태를 변경했습니다.'),
  ).toBeInTheDocument()
  expect(dialog.getByText('해결')).toBeInTheDocument()
  const table = within(screen.getByRole('table', { name: '정합성 문제 목록' }))
  expect(await table.findByText('해결')).toBeInTheDocument()
  expect(change).toHaveBeenCalledTimes(1)
})

test('상태 변경 충돌의 원인을 표시하고 재전송 없이 최신 상태를 조회한다', async () => {
  let issue = { ...issueFixture }
  const change = vi.fn(() => {
    issue = { ...issue, status: 'CHECKING', rowVersion: 1 }
    return HttpResponse.json(
      {
        error: {
          code: 'VERSION_CONFLICT',
          message: '다른 사용자가 먼저 변경했습니다.',
        },
      },
      { status: 409 },
    )
  })
  server.use(
    http.get('/api/v1/issues/1', () => HttpResponse.json({ data: issue })),
    http.patch('/api/v1/issues/1/status', change),
  )
  const user = userEvent.setup()
  open('?view=issues&issue=1')
  const dialog = within(await screen.findByRole('dialog'))
  await user.selectOptions(
    await dialog.findByLabelText('변경할 상태'),
    'CHECKING',
  )
  await user.type(dialog.getByLabelText('변경 사유'), '확인 시작')
  await user.click(dialog.getByRole('button', { name: '상태 변경' }))
  expect(await dialog.findByRole('alert')).toHaveTextContent(
    '다른 사용자가 먼저 변경했습니다.',
  )
  expect(dialog.getByText('확인중')).toBeInTheDocument()
  expect(dialog.getByLabelText('변경할 상태')).toHaveValue('')
  expect(change).toHaveBeenCalledTimes(1)
})
