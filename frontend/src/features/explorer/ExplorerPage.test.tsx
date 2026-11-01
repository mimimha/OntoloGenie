import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import ExplorerPage from './ExplorerPage'

function renderExplorerPage() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ExplorerPage />
    </QueryClientProvider>,
  )
}

test('객체 목록 API로 받은 객체를 보여준다', async () => {
  renderExplorerPage()

  expect(screen.getByText('불러오는 중…')).toBeInTheDocument()
  expect(await screen.findByText('프레스 3호기')).toBeInTheDocument()
  expect(screen.getByText('6건')).toBeInTheDocument()
  expect(screen.getAllByText('설비')).toHaveLength(2)
  expect(
    screen.getByText('왼쪽 목록에서 객체를 선택해 주세요.'),
  ).toBeInTheDocument()
})

test('객체를 고르면 상세 API로 받은 정보를 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(
    await screen.findByRole('heading', { name: '프레스 3호기' }),
  ).toBeInTheDocument()
  expect(screen.getByText('설비 / #301')).toBeInTheDocument()
  expect(screen.getByText('MES · mes.equipment')).toBeInTheDocument()
  expect(screen.getByText('설비 코드')).toBeInTheDocument()
})

test('권한으로 가린 속성은 권한 없음, 값이 없는 속성은 -로 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  const purchasePrice = await screen.findByText('구매 가격')
  expect(purchasePrice.nextElementSibling).toHaveTextContent('권한 없음')
  const maintenanceNote = screen.getByText('정비 메모')
  expect(maintenanceNote.nextElementSibling).toHaveTextContent('-')
})

test('속성 표시 이름이 없으면 속성 코드를 보여준다', async () => {
  server.use(
    http.get('/api/v1/objects/:id', () =>
      HttpResponse.json({
        data: {
          id: 301,
          objectTypeId: 3,
          objectTypeName: '설비',
          displayName: '프레스 3호기',
          properties: { equipment_code: 'PR-03', vendor_code: 'V-01' },
          propertyNames: { equipment_code: '설비 코드' },
          restrictedPropertyCodes: [],
          status: 'ACTIVE',
          firstCollectedAt: '2026-10-26T09:00:00+09:00',
        },
      }),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(await screen.findByText('설비 코드')).toBeInTheDocument()
  expect(screen.getByText('vendor_code')).toBeInTheDocument()
})

test('검색 결과가 없으면 안내 문구를 보여준다', async () => {
  server.use(
    http.get('/api/v1/objects', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      }),
    ),
  )
  renderExplorerPage()

  expect(
    await screen.findByText('조건에 맞는 객체가 없습니다.'),
  ).toBeInTheDocument()
  expect(screen.getByText('0건')).toBeInTheDocument()
})

test('검색어로 검색하면 맞는 객체만 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.type(await screen.findByLabelText('객체 검색어'), '프레스')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('3건')).toBeInTheDocument()
  expect(screen.getByText('프레스 작업')).toBeInTheDocument()
  expect(screen.queryByText('야간 인수인계')).not.toBeInTheDocument()
})
