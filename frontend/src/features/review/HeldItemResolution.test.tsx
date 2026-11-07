import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import { server } from '../../mocks/node'
import HeldItems from './HeldItems'
import HeldItemResolution from './HeldItemResolution'
import type { HeldItem } from './heldItemApi'
import {
  validateHeldResolution,
  type HeldResolutionAccess,
  type HeldResolutionAvailability,
  type HeldResolutionInput,
  type HeldResolutionResult,
} from './heldResolution'

const item = (overrides: Partial<HeldItem> = {}): HeldItem => ({
  id: 801,
  proposalItemId: 303,
  linkMappingId: 7,
  sourceObjectId: 5,
  linkTypeId: 9,
  status: 'HELD',
  rawValue: 'TEST-RAW',
  transformedValue: 'TEST',
  holdReason: '대상 확인 필요',
  resolvedLinkId: null,
  resolvedSyncRunId: null,
  resolvedBy: null,
  resolvedAt: null,
  resolutionNote: null,
  createdAt: '2026-11-07T10:00:00Z',
  ...overrides,
})
const policy = (
  overrides: Partial<HeldResolutionAvailability> = {},
): HeldResolutionAvailability => ({
  heldItemId: 801,
  status: 'HELD',
  description: '검증용 처리 정책',
  reasonRequired: true,
  reasonMaxLength: 50,
  actions: [
    { decision: 'APPROVE', allowed: true, unavailableReason: null },
    { decision: 'DISMISS', allowed: true, unavailableReason: null },
  ],
  ...overrides,
})
const result = (input: HeldResolutionInput): HeldResolutionResult => ({
  item: item({
    status: input.decision === 'APPROVE' ? 'APPROVED' : 'DISMISSED',
    resolvedLinkId: input.decision === 'APPROVE' ? 901 : null,
    resolvedBy: 1,
    resolvedAt: '2026-11-07T10:30:00Z',
    resolutionNote: input.reason,
  }),
  actionExecutionId: 1001,
})
function service() {
  return {
    availability: vi.fn(async () => policy()),
    resolve: vi.fn(async (_id: number, input: HeldResolutionInput) =>
      result(input),
    ),
  }
}
function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}
function open(
  access?: HeldResolutionAccess,
  options: {
    current?: HeldItem
    refresh?: () => Promise<HeldItem | undefined>
  } = {},
) {
  const refresh = options.refresh ?? vi.fn(async () => item())
  const queryClient = client()
  render(
    <QueryClientProvider client={queryClient}>
      <HeldItemResolution
        item={options.current ?? item()}
        access={access}
        onRefresh={refresh}
      />
    </QueryClientProvider>,
  )
  return { user: userEvent.setup(), refresh, queryClient }
}
const approve = () =>
  screen.getByRole('button', { name: '수동 승인 내용 확인' })
const dismiss = () => screen.getByRole('button', { name: '종결 내용 확인' })
async function ready() {
  await waitFor(() => expect(approve()).toBeEnabled())
}
async function fill(
  user: ReturnType<typeof userEvent.setup>,
  target = '12',
  reason = '확인 후 처리',
) {
  if (target)
    await user.type(
      screen.getByRole('textbox', { name: '승인할 대상 객체 ID' }),
      target,
    )
  if (reason)
    await user.type(
      screen.getByRole('textbox', { name: '처리 사유 (필수)' }),
      reason,
    )
}

test('058·정책 미연결 시 동작을 막고 준비 중으로 표시한다', () => {
  open()
  expect(screen.getByText(/수동 승인·종결은 준비 중/)).toBeInTheDocument()
  expect(approve()).toBeDisabled()
  expect(dismiss()).toBeDisabled()
  expect(
    screen.getByRole('textbox', { name: '승인할 대상 객체 ID' }),
  ).toBeDisabled()
})

test.each(['APPROVED', 'AUTO_RESOLVED', 'DISMISSED', 'UNKNOWN'])(
  '이미 처리된 상태 또는 미지원 상태 %s는 읽기 전용이다',
  (status) => {
    const api = service()
    open(api, { current: item({ status }) })
    expect(
      screen.getByText('이미 처리됐거나 수동 처리할 수 없는 상태입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(api.availability).not.toHaveBeenCalled()
    expect(api.resolve).not.toHaveBeenCalled()
  },
)

test('허용되지 않은 동작과 재검사 차단 사유를 표시한다', async () => {
  const api = service()
  api.availability.mockResolvedValue(
    policy({
      actions: [
        {
          decision: 'APPROVE',
          allowed: false,
          unavailableReason: '재검사 결과 확인 필요',
        },
        { decision: 'DISMISS', allowed: true, unavailableReason: null },
      ],
    }),
  )
  open(api)
  await screen.findByText('재검사 결과 확인 필요')
  expect(approve()).toBeDisabled()
  expect(dismiss()).toBeEnabled()
})

test.each([
  policy({ heldItemId: 802 }),
  policy({ status: 'APPROVED' }),
  policy({ description: '' }),
  policy({ reasonMaxLength: 0 }),
])(
  '대상·상태·정책 응답이 잘못되면 실행하지 않는다 ($heldItemId, $status)',
  async (data) => {
    const api = service()
    api.availability.mockResolvedValue(data)
    open(api)
    await screen.findByRole('alert')
    expect(approve()).toBeDisabled()
    expect(dismiss()).toBeDisabled()
    expect(api.resolve).not.toHaveBeenCalled()
  },
)

test('사유와 대상을 확인한 후에만 승인하고 처리 결과·감사 실행을 표시한다', async () => {
  const api = service()
  const { user } = open(api)
  await ready()
  await fill(user)
  await user.click(approve())
  const confirm = within(
    screen.getByRole('group', { name: '처리 내용 최종 확인' }),
  )
  expect(confirm.getByText(/대상 객체 #12로 수동 연결/)).toBeInTheDocument()
  expect(api.resolve).not.toHaveBeenCalled()
  await user.click(confirm.getByRole('button', { name: '최종 승인' }))
  await screen.findByText('수동 승인 처리가 완료되었습니다.')
  expect(api.resolve).toHaveBeenCalledWith(801, {
    decision: 'APPROVE',
    targetObjectId: 12,
    reason: '확인 후 처리',
  })
  expect(screen.getByText('생성된 연결 #901')).toBeInTheDocument()
  expect(screen.getByText('감사 실행 #1001')).toBeInTheDocument()
  expect(screen.getByText('처리 기록: 확인 후 처리')).toBeInTheDocument()
  expect(approve()).toBeDisabled()
})

test('종결에는 입력한 대상 객체를 보내지 않으며 연결 생성 결과를 표시하지 않는다', async () => {
  const api = service()
  const { user } = open(api)
  await ready()
  await fill(user, '5')
  await user.click(dismiss())
  expect(
    screen.getByText('연결을 만들지 않고 이 보류 건을 종결합니다.'),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '최종 종결' }))
  await screen.findByText('종결 처리가 완료되었습니다.')
  expect(api.resolve).toHaveBeenCalledWith(801, {
    decision: 'DISMISS',
    reason: '확인 후 처리',
  })
  expect(screen.queryByText(/생성된 연결/)).not.toBeInTheDocument()
})

test.each(['', '0', '-1', '1.5', '1e3', '9007199254740992', '5'])(
  '잘못된 대상 객체 %s는 승인하지 않는다',
  async (target) => {
    const api = service()
    const { user } = open(api)
    await ready()
    await fill(user, target)
    await user.click(approve())
    await screen.findByRole('alert')
    expect(
      screen.queryByRole('group', { name: '처리 내용 최종 확인' }),
    ).not.toBeInTheDocument()
    expect(api.resolve).not.toHaveBeenCalled()
  },
)

test('확정 정책의 필수 사유·길이를 검증하고 입력으로 돌아갈 수 있다', async () => {
  const api = service()
  const { user } = open(api)
  await ready()
  await fill(user, '12', ' ')
  await user.click(dismiss())
  expect(screen.getByRole('alert')).toHaveTextContent(
    '처리 사유를 입력해 주세요.',
  )
  const note = screen.getByRole('textbox', { name: '처리 사유 (필수)' })
  await user.clear(note)
  await user.type(note, 'a'.repeat(51))
  await user.click(dismiss())
  expect(screen.getByRole('alert')).toHaveTextContent('50자 이내')
  await user.clear(note)
  await user.type(note, '정상 사유')
  await user.click(dismiss())
  await user.click(screen.getByRole('button', { name: '입력으로 돌아가기' }))
  expect(note).toBeEnabled()
  expect(api.resolve).not.toHaveBeenCalled()
})

test('사유 선택 정책을 받으면 빈 사유 종결을 허용한다', async () => {
  const api = service()
  api.availability.mockResolvedValue(
    policy({ reasonRequired: false, reasonMaxLength: null }),
  )
  const { user } = open(api)
  await ready()
  await user.click(dismiss())
  await user.click(screen.getByRole('button', { name: '최종 종결' }))
  await screen.findByText('종결 처리가 완료되었습니다.')
  expect(api.resolve).toHaveBeenCalledWith(801, {
    decision: 'DISMISS',
    reason: '',
  })
})

test('입력 중 Enter는 처리하지 않고 최종 버튼 중복 클릭도 한 번만 요청한다', async () => {
  const api = service()
  let finish!: (data: HeldResolutionResult) => void
  api.resolve.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const { user } = open(api)
  await ready()
  await fill(user, '12{Enter}')
  expect(api.resolve).not.toHaveBeenCalled()
  await user.click(approve())
  await user.dblClick(screen.getByRole('button', { name: '최종 승인' }))
  expect(api.resolve).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button', { name: '최종 승인' })).toBeDisabled()
  await act(async () =>
    finish(
      result({
        decision: 'APPROVE',
        targetObjectId: 12,
        reason: '확인 후 처리',
      }),
    ),
  )
  await screen.findByText('수동 승인 처리가 완료되었습니다.')
})

test.each(['conflict', 'forbidden', 'not-found', 'network', 'server'] as const)(
  '처리 오류 %s는 재실행을 막고 최신 상태 확인을 요구한다',
  async (kind) => {
    const api = service()
    api.resolve.mockRejectedValue(
      new ApiError('PRIVATE-RESOLUTION-DATA', {
        kind,
        status:
          kind === 'conflict'
            ? 409
            : kind === 'forbidden'
              ? 403
              : kind === 'not-found'
                ? 404
                : 500,
      }),
    )
    const { user } = open(api)
    await ready()
    await fill(user)
    await user.click(dismiss())
    await user.click(screen.getByRole('button', { name: '최종 종결' }))
    await screen.findByRole('alert')
    expect(
      screen.queryByText('PRIVATE-RESOLUTION-DATA'),
    ).not.toBeInTheDocument()
    expect(dismiss()).toBeDisabled()
    expect(
      screen.getByRole('button', { name: '최신 보류 건 다시 확인' }),
    ).toBeEnabled()
    expect(api.resolve).toHaveBeenCalledTimes(1)
  },
)

test('409 이후 최신 보류 상태와 정책 재확인 후에만 다시 처리할 수 있다', async () => {
  const api = service()
  api.resolve.mockRejectedValueOnce(
    new ApiError('conflict', { kind: 'conflict', status: 409 }),
  )
  const refresh = vi.fn(async () => item())
  const { user } = open(api, { refresh })
  await ready()
  await fill(user)
  await user.click(dismiss())
  await user.click(screen.getByRole('button', { name: '최종 종결' }))
  await screen.findByRole('alert')
  await user.click(
    screen.getByRole('button', { name: '최신 보류 건 다시 확인' }),
  )
  await ready()
  expect(refresh).toHaveBeenCalledTimes(1)
  expect(api.availability).toHaveBeenCalledTimes(2)
  await user.click(dismiss())
  await user.click(screen.getByRole('button', { name: '최종 종결' }))
  await screen.findByText('종결 처리가 완료되었습니다.')
})

test('422 대상 오류는 성공으로 표시하지 않고 입력을 수정할 수 있다', async () => {
  const api = service()
  api.resolve.mockRejectedValueOnce(
    new ApiError('raw', {
      kind: 'validation',
      status: 422,
      code: 'HELD_ITEM_TARGET_INVALID',
    }),
  )
  const { user } = open(api)
  await ready()
  await fill(user)
  await user.click(approve())
  await user.click(screen.getByRole('button', { name: '최종 승인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '관계 유형에 맞는 대상 객체인지',
  )
  expect(
    screen.queryByText('수동 승인 처리가 완료되었습니다.'),
  ).not.toBeInTheDocument()
  expect(
    screen.getByRole('textbox', { name: '승인할 대상 객체 ID' }),
  ).toBeEnabled()
})

test('다른 건·잘못된 처리 상태·연결 또는 감사 누락 응답은 성공으로 인정하지 않는다', () => {
  const input: HeldResolutionInput = {
    decision: 'APPROVE',
    targetObjectId: 12,
    reason: '검증',
  }
  const success = result(input)
  for (const data of [
    { ...success, item: { ...success.item, id: 802 } },
    { ...success, item: { ...success.item, status: 'HELD' } },
    { ...success, item: { ...success.item, resolvedLinkId: null } },
    { ...success, actionExecutionId: 0 },
  ])
    expect(() => validateHeldResolution(801, input, data)).toThrow(ApiError)
})

test('성공 후 실제 목록·상세를 서버 결과로 갱신하고 처리 중 상세 닫기를 막는다', async () => {
  const api = service()
  let current = item()
  let finish!: (data: HeldResolutionResult) => void
  api.resolve.mockImplementation(
    () =>
      new Promise((done) => {
        finish = (data) => {
          current = data.item
          done(data)
        }
      }),
  )
  server.use(
    http.get('/api/v1/held-items', () =>
      HttpResponse.json({
        data: [current],
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      }),
    ),
    http.get('/api/v1/held-items/801', () =>
      HttpResponse.json({ data: current }),
    ),
  )
  render(
    <QueryClientProvider client={client()}>
      <HeldItems page={0} onPageChange={() => {}} resolutionAccess={api} />
    </QueryClientProvider>,
  )
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: '보류 건 #801' }))
  await ready()
  await fill(user)
  await user.click(dismiss())
  await user.click(screen.getByRole('button', { name: '최종 종결' }))
  await user.click(screen.getByRole('button', { name: '닫기' }))
  expect(screen.getByRole('dialog')).toBeInTheDocument()
  await act(async () =>
    finish(result({ decision: 'DISMISS', reason: '확인 후 처리' })),
  )
  await screen.findByText('종결 처리가 완료되었습니다.')
  expect(
    screen.getByText('이미 처리됐거나 수동 처리할 수 없는 상태입니다.'),
  ).toBeInTheDocument()
  await waitFor(() =>
    expect(
      within(
        screen.getByRole('table', { name: '개별 보류 건 목록' }),
      ).getByText('종결'),
    ).toBeInTheDocument(),
  )
  await user.click(screen.getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('캐시의 보류 상태로 실행하지 않고 최신 상세가 끝날 때까지 기다린다', async () => {
  const api = service()
  const queryClient = client()
  queryClient.setQueryData(['held-items', 'detail', 801], item())
  server.use(
    http.get('/api/v1/held-items', () =>
      HttpResponse.json({
        data: [item()],
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      }),
    ),
    http.get('/api/v1/held-items/801', async () => {
      await delay(150)
      return HttpResponse.json({ data: item({ status: 'AUTO_RESOLVED' }) })
    }),
  )
  render(
    <QueryClientProvider client={queryClient}>
      <HeldItems page={0} onPageChange={() => {}} resolutionAccess={api} />
    </QueryClientProvider>,
  )
  await userEvent.click(
    await screen.findByRole('button', { name: '보류 건 #801' }),
  )
  expect(
    screen.queryByRole('button', { name: '수동 승인 내용 확인' }),
  ).not.toBeInTheDocument()
  await screen.findByText('이미 처리됐거나 수동 처리할 수 없는 상태입니다.')
  expect(api.availability).not.toHaveBeenCalled()
  expect(api.resolve).not.toHaveBeenCalled()
})
