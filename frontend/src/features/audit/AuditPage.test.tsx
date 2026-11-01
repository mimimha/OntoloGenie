import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import AuditPage from './AuditPage'

function renderAuditPage(path: string) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/explorer" element={<p>탐색 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('객체 번호가 있으면 그 객체의 실행 이력을 보여준다', async () => {
  renderAuditPage('/audit?objectId=301')

  expect(screen.getByRole('heading', { name: '실행 이력' })).toBeInTheDocument()
  expect(await screen.findByText('3건')).toBeInTheDocument()
  expect(await screen.findByDisplayValue('프레스 3호기')).toBeInTheDocument()
  expect(
    screen.getByText('대체 설비 PR-02는 프레스 공정을 처리할 수 없습니다.'),
  ).toBeInTheDocument()
  expect(
    screen.getByText('reassignment_plan · id: 501 · 생성'),
  ).toBeInTheDocument()
})

test('객체 번호가 없으면 내 실행 이력을 보여준다', async () => {
  renderAuditPage('/audit')

  expect(await screen.findByText('4건')).toBeInTheDocument()
  expect(screen.getByText('sync_run #77')).toBeInTheDocument()
  expect(screen.queryByLabelText('대상')).not.toBeInTheDocument()
})

test('결과로 검색하고 초기화하면 다시 전체를 보여준다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit?objectId=301')
  await screen.findByText('3건')

  await user.selectOptions(screen.getByLabelText('결과'), 'BLOCKED')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('1건')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(await screen.findByText('3건')).toBeInTheDocument()
})

test('기간으로 검색하면 그 기간의 이력만 보여준다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit?objectId=301')
  await screen.findByText('3건')

  await user.type(screen.getByLabelText('시작일'), '2026-10-26')
  await user.type(screen.getByLabelText('종료일'), '2026-10-26')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('1건')).toBeInTheDocument()
})

test('시작일이 종료일보다 늦으면 검색하지 않고 안내한다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit?objectId=301')
  await screen.findByText('3건')

  await user.type(screen.getByLabelText('시작일'), '2026-10-27')
  await user.type(screen.getByLabelText('종료일'), '2026-10-26')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(screen.getByRole('alert')).toHaveTextContent(
    '종료일은 시작일 이후로 선택해 주세요.',
  )
  expect(screen.getByText('3건')).toBeInTheDocument()
})

test('실행 이력 조회가 실패하면 오류 안내를 보여준다', async () => {
  server.use(
    http.get('/api/v1/me/executions', () =>
      HttpResponse.json(
        {
          error: {
            code: 'INTERNAL_ERROR',
            message: '서버 내부 오류',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 500 },
      ),
    ),
  )
  renderAuditPage('/audit')

  expect(await screen.findByText('서버 내부 오류')).toBeInTheDocument()
})

test('처음 들어온 화면에서 이전 화면으로를 누르면 탐색 화면으로 간다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit')

  await user.click(screen.getByRole('button', { name: '이전 화면으로' }))

  expect(screen.getByText('탐색 화면')).toBeInTheDocument()
})
