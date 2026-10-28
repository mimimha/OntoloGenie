import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
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
  expect(screen.getByText('equipment_code')).toBeInTheDocument()
})
