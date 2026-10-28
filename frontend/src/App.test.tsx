import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import App from './App'

test('주 메뉴를 누르면 화면과 선택된 메뉴가 바뀐다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient()}>
      <App />
    </QueryClientProvider>,
  )

  const explorerMenu = screen.getByRole('link', { name: '업무 데이터' })
  const agentMenu = screen.getByRole('link', { name: 'AI 업무 도우미' })
  expect(explorerMenu).toHaveAttribute('aria-current', 'page')
  expect(await screen.findByText('프레스 3호기')).toBeInTheDocument()

  await user.click(agentMenu)

  expect(
    screen.getByRole('heading', { name: 'AI 업무 도우미' }),
  ).toBeInTheDocument()
  expect(agentMenu).toHaveAttribute('aria-current', 'page')
  expect(explorerMenu).not.toHaveAttribute('aria-current')
})
