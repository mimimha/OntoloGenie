import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, useNavigate } from 'react-router'
import { expect, test, vi } from 'vitest'
import { issueFixture } from '../../mocks/issueHandlers'
import { server } from '../../mocks/node'
import ProposalsPage from '../review/ProposalsPage'

function HistoryControl() {
  const navigate = useNavigate()
  return (
    <button type="button" onClick={() => navigate(-1)}>
      브라우저 뒤로 가기
    </button>
  )
}

function open(search = '', previousSearch?: string) {
  const entries = [`/admin/rules${search}`]
  if (previousSearch !== undefined)
    entries.unshift(`/admin/rules${previousSearch}`)
  render(
    <QueryClientProvider client={new QueryClient()}>
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
  expect(screen.getByRole('heading', { name: '검토 제안' })).toBeInTheDocument()
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
  expect(screen.getByRole('heading', { name: '검토 제안' })).toBeInTheDocument()
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
    requests.map((params) => [
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
