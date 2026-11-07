import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import { server } from '../../mocks/node'
import SourceSamples from './SourceSamples'
import type {
  SamplePreview,
  SampleReceipt,
  SourceSampleAccess,
} from './samplePreview'

const receipt = (): SampleReceipt => ({
  previewId: 41,
  joined: false,
  expiresAt: new Date(Date.now() + 60000).toISOString(),
})
const result = (): SamplePreview => ({
  status: 'SUCCEEDED',
  columns: ['code', 'private', 'missing', 'null', 'empty', 'zero', 'flag'],
  rows: [
    [
      { kind: 'visible', value: 'TEST-001' },
      { kind: 'masked' },
      { kind: 'unavailable' },
      { kind: 'visible', value: null },
      { kind: 'visible', value: '' },
      { kind: 'visible', value: 0 },
      { kind: 'visible', value: false },
    ],
  ],
})
function service() {
  return {
    policyDescription: '검증용 표시 정책',
    request: vi.fn(async () => receipt()),
    read: vi.fn(async () => result()),
  }
}
function mount(access?: SourceSampleAccess) {
  server.use(
    http.get('/api/v1/sources/:id/resources', ({ params }) =>
      HttpResponse.json({
        data: [
          {
            id: 10,
            dataSourceId: Number(params.id),
            resourceKey: 'test_table',
            resourceKind: 'TABLE',
            isReadable: true,
            sourceMetadata: {},
          },
          {
            id: 11,
            dataSourceId: Number(params.id),
            resourceKey: 'next_table',
            resourceKind: 'TABLE',
            isReadable: true,
            sourceMetadata: {},
          },
          {
            id: 12,
            dataSourceId: Number(params.id),
            resourceKey: 'blocked_table',
            resourceKind: 'TABLE',
            isReadable: false,
            sourceMetadata: {},
          },
          {
            id: 13,
            dataSourceId: Number(params.id),
            resourceKey: 'test_view',
            resourceKind: 'VIEW',
            isReadable: true,
            sourceMetadata: {},
          },
        ],
      }),
    ),
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={client}>
      <SourceSamples sourceId={1} access={access} />
    </QueryClientProvider>,
  )
  return { ...view, client }
}
async function select() {
  const user = userEvent.setup()
  await screen.findByRole('option', { name: 'test_table' })
  await user.selectOptions(
    screen.getByRole('combobox', { name: '표본 조회 대상' }),
    '10',
  )
  return user
}

test('API와 정책 미연결 시 요청을 막고 읽기 불가·테이블 외 대상을 비활성화한다', async () => {
  mount()
  const user = await select()
  expect(screen.getByText(/표본 조회는 준비 중/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '표본 조회 요청' })).toBeDisabled()
  expect(
    screen.getByRole('option', { name: 'blocked_table (읽기 불가)' }),
  ).toBeDisabled()
  expect(
    screen.getByRole('option', { name: 'test_view (테이블 아님)' }),
  ).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
})

test('정책 설명이 비어 있으면 어댑터가 있어도 실행하지 않는다', async () => {
  const api = service()
  mount({ ...api, policyDescription: ' ' })
  await select()
  expect(screen.getByRole('button', { name: '표본 조회 요청' })).toBeDisabled()
  expect(api.request).not.toHaveBeenCalled()
})

test('가림·누락·NULL·빈 문자열·0·false를 구분하고 표본은 쿼리 캐시에 저장하지 않는다', async () => {
  const api = service()
  const { client } = mount(api)
  const user = await select()
  await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
  await screen.findByRole('table', { name: '원천 행 표본' })
  for (const text of [
    'TEST-001',
    '가림',
    '제공 안 됨',
    'NULL',
    '빈 문자열',
    '0',
    'false',
  ])
    expect(screen.getByRole('cell', { name: text })).toBeInTheDocument()
  expect(api.request).toHaveBeenCalledWith(1, 10, 20)
  expect(
    JSON.stringify(
      client
        .getQueryCache()
        .getAll()
        .map((q) => q.state.data),
    ),
  ).not.toContain('TEST-001')
})

test.each(['0', '21', '1.5', ''])(
  '잘못된 행 수 %s는 접수하지 않는다',
  async (value) => {
    const api = service()
    mount(api)
    const user = await select()
    const input = screen.getByRole('spinbutton', { name: '표본 행 수 (1~20)' })
    await user.clear(input)
    if (value) await user.type(input, value)
    expect(
      screen.getByRole('button', { name: '표본 조회 요청' }),
    ).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent('1~20')
    expect(api.request).not.toHaveBeenCalled()
  },
)

test('중복 클릭을 막고 접수·대기·실행·성공을 표시하며 기존 요청 합류를 안내한다', async () => {
  let accept!: (value: SampleReceipt) => void
  const api = service()
  api.request.mockImplementation(
    () =>
      new Promise((resolve) => {
        accept = resolve
      }),
  )
  api.read
    .mockResolvedValueOnce({ ...result(), status: 'QUEUED', rows: [] })
    .mockResolvedValueOnce({ ...result(), status: 'RUNNING', rows: [] })
    .mockResolvedValue(result())
  mount(api)
  const user = await select()
  await user.dblClick(screen.getByRole('button', { name: '표본 조회 요청' }))
  expect(api.request).toHaveBeenCalledTimes(1)
  expect(screen.getByText('표본 조회를 요청하는 중…')).toBeInTheDocument()
  await act(async () => accept({ ...receipt(), joined: true }))
  await screen.findByText('표본 조회 대기 중…')
  expect(screen.getByText(/기존 진행 중인 요청/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '표본 조회 요청' })).toBeDisabled()
  await screen.findByText('표본 조회 실행 중…')
  await screen.findByText('TEST-001')
  expect(api.read).toHaveBeenCalledTimes(3)
})

test.each(['forbidden', 'conflict', 'server'] as const)(
  '접수 실패 %s는 민감 오류 원문 없이 표시한다',
  async (kind) => {
    const api = service()
    api.request.mockRejectedValue(
      new ApiError('SECRET-RAW-VALUE', {
        kind,
        status: kind === 'forbidden' ? 403 : kind === 'conflict' ? 409 : 500,
      }),
    )
    const { container } = mount(api)
    const user = await select()
    await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
    await screen.findByRole('alert')
    expect(container.innerHTML).not.toContain('SECRET-RAW-VALUE')
    expect(api.read).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '표본 조회 요청' })).toBeEnabled()
  },
)

test('상태 조회 실패는 자동 재접수하지 않고 조회만 재시도한다', async () => {
  const api = service()
  api.read
    .mockRejectedValueOnce(new Error('SECRET-RAW-VALUE'))
    .mockResolvedValue(result())
  mount(api)
  const user = await select()
  await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
  await screen.findByRole('alert')
  expect(screen.getByRole('button', { name: '표본 조회 요청' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  await screen.findByText('TEST-001')
  expect(api.request).toHaveBeenCalledTimes(1)
  expect(api.read).toHaveBeenCalledTimes(2)
})

test.each(['empty', 'failed', 'expired'] as const)(
  '빈 결과·실행 실패·404 %s를 구분한다',
  async (state) => {
    const api = service()
    if (state === 'expired')
      api.read
        .mockRejectedValueOnce(
          new ApiError('gone', { kind: 'not-found', status: 404 }),
        )
        .mockResolvedValue(result())
    else
      api.read.mockResolvedValue({
        ...result(),
        status: state === 'failed' ? 'FAILED' : 'SUCCEEDED',
        rows: [],
      })
    mount(api)
    const user = await select()
    await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
    await screen.findByText(
      state === 'empty'
        ? '조회된 표본 행이 없습니다.'
        : state === 'failed'
          ? /표본을 읽지 못했습니다/
          : /표본이 만료되었거나/,
    )
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '표본 조회 요청' })).toBeEnabled()
    if (state === 'expired') {
      await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
      await screen.findByText('TEST-001')
      expect(api.request).toHaveBeenCalledTimes(2)
    }
  },
)

test('만료 시 표시된 표본을 제거한다', async () => {
  const api = service()
  api.request.mockResolvedValue({
    ...receipt(),
    expiresAt: new Date(Date.now() + 1600).toISOString(),
  })
  mount(api)
  const user = await select()
  await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
  await screen.findByText('TEST-001')
  await screen.findByText(/표본이 만료되었거나/)
  expect(screen.queryByText('TEST-001')).not.toBeInTheDocument()
})

test('대상 전환 시 이전 표본과 늦게 도착한 응답을 표시하지 않는다', async () => {
  let finish!: (value: SamplePreview) => void
  const api = service()
  api.read.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  mount(api)
  const user = await select()
  await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
  await waitFor(() => expect(api.read).toHaveBeenCalledTimes(1))
  await user.selectOptions(screen.getByRole('combobox'), '11')
  await act(async () => finish(result()))
  expect(screen.queryByText('TEST-001')).not.toBeInTheDocument()
  expect(screen.queryByText(/표본 요청 #41/)).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
  await screen.findByText('TEST-001')
  expect(api.request).toHaveBeenLastCalledWith(1, 11, 20)
})
