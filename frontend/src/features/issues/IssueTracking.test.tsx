import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import IssueTracking from './IssueTracking'

const statusLabels = { NEW: '신규', CHECKING: '확인중' }

test('연동 전에는 전달 기록을 막고 이력을 빈 결과로 표시하지 않는다', () => {
  render(
    <IssueTracking
      issue={{ forwardedAt: null, forwardedBy: null }}
      statusLabels={statusLabels}
    />,
  )
  expect(screen.getByRole('button', { name: '전달함으로 기록' })).toBeDisabled()
  expect(
    screen.getByText('상태 변경 이력 조회는 아직 사용할 수 없습니다.'),
  ).toBeInTheDocument()
})

test('전달 기록을 요청하고 처리 결과와 상태 이력을 표시한다', async () => {
  const record = vi.fn()
  const changedAt = '2026-11-06T10:00:00Z'
  const history = {
    state: 'ready' as const,
    entries: [
      {
        id: 1,
        previousStatus: 'NEW',
        status: 'CHECKING',
        changedAt,
        actor: '계정 #5',
        reason: '원천 값 확인 시작',
      },
    ],
  }
  const { rerender } = render(
    <IssueTracking
      issue={{ forwardedAt: null, forwardedBy: null }}
      statusLabels={statusLabels}
      forwarding={{ record, pending: false, error: null }}
      history={history}
    />,
  )
  await userEvent.click(screen.getByRole('button', { name: '전달함으로 기록' }))
  expect(record).toHaveBeenCalledTimes(1)
  rerender(
    <IssueTracking
      issue={{ forwardedAt: changedAt, forwardedBy: 5 }}
      statusLabels={statusLabels}
      forwarding={{ record, pending: false, error: null }}
      history={history}
    />,
  )
  const delivery = within(
    screen.getByRole('region', { name: '원천 전달 기록' }),
  )
  expect(delivery.getByText('전달됨')).toBeInTheDocument()
  expect(delivery.getByText('계정 #5')).toBeInTheDocument()
  expect(delivery.queryByRole('button')).not.toBeInTheDocument()
  const entries = within(screen.getByRole('region', { name: '상태 변경 이력' }))
  expect(entries.getByText('신규 → 확인중')).toBeInTheDocument()
  expect(entries.getByText('원천 값 확인 시작')).toBeInTheDocument()
})
