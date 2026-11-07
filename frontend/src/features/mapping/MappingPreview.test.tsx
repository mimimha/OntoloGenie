import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import MappingPreview from './MappingPreview'
import {
  PreviewDraftError,
  type MappingPreviewDraft,
  type MappingPreviewReceipt,
  type MappingPreviewResult,
} from './previewModel'

const draft: MappingPreviewDraft = {
  sourceResourceId: 11,
  objectTypeId: 501,
  keyColumns: ['item_code'],
  rowFilter: {},
  isActive: true,
  properties: [],
}
const receipt = (): MappingPreviewReceipt => ({
  previewId: 51,
  joined: false,
  expiresAt: new Date(Date.now() + 60000).toISOString(),
})
const output = (): MappingPreviewResult => ({
  status: 'SUCCEEDED',
  rows: [
    {
      rowNumber: 1,
      objectKey: { kind: 'visible', value: 'TEST-001' },
      values: [
        {
          source: 'item_name',
          property: '품목명',
          before: { kind: 'visible', value: ' Widget ' },
          after: { kind: 'visible', value: 'Widget' },
        },
        {
          source: 'private',
          property: '비공개',
          before: { kind: 'masked' },
          after: { kind: 'unavailable' },
        },
        {
          source: 'flag',
          property: '여부',
          before: { kind: 'visible', value: false },
          after: { kind: 'visible', value: 0 },
        },
        {
          source: 'empty',
          property: '빈 값',
          before: { kind: 'visible', value: null },
          after: { kind: 'visible', value: '' },
        },
      ],
    },
  ],
  rowErrors: [
    {
      rowNumber: 2,
      property: '수량',
      step: 1,
      code: 'CONVERSION_FAILED',
      reason: '정수로 변환할 수 없습니다.',
      before: { kind: 'masked' },
    },
  ],
})
function service() {
  return {
    policyDescription: '검증용 정책',
    request: vi.fn(async () => receipt()),
    read: vi.fn(async () => output()),
  }
}
const requestButton = () =>
  screen.getByRole('button', { name: '변환 미리보기 요청' })

test('API나 표시 정책이 없으면 실행하지 않는다', async () => {
  const prepare = vi.fn(async () => draft)
  const { rerender } = render(<MappingPreview prepareDraft={prepare} />)
  expect(requestButton()).toBeDisabled()
  expect(screen.getByText(/변환 미리보기는 준비 중/)).toBeInTheDocument()
  const api = service()
  rerender(
    <MappingPreview
      access={{ ...api, policyDescription: ' ' }}
      prepareDraft={prepare}
    />,
  )
  await userEvent.click(requestButton())
  expect(prepare).not.toHaveBeenCalled()
  expect(api.request).not.toHaveBeenCalled()
})

test('변환 전후·부분 오류·가림·미제공·빈 값을 구분한다', async () => {
  const api = service()
  const data = output()
  Object.assign(data.rows[0].values[1].before, { value: 'HIDDEN-RAW-VALUE' })
  api.read.mockResolvedValue(data)
  const { container } = render(
    <MappingPreview access={api} prepareDraft={async () => draft} />,
  )
  await userEvent.click(requestButton())
  await screen.findByRole('table', { name: '표본 1행 변환 전후' })
  for (const text of ['가림', '미제공', 'false', '0', 'NULL', '빈 문자열'])
    expect(screen.getByRole('cell', { name: text })).toBeInTheDocument()
  expect(
    screen.getByText(/변환된 객체 1건 · 행별 오류 1건/),
  ).toBeInTheDocument()
  expect(screen.getByText(/대상 속성 수량 · 변환 단계 1/)).toBeInTheDocument()
  expect(screen.getByText(/CONVERSION_FAILED/)).toHaveTextContent(
    '정수로 변환할 수 없습니다.',
  )
  expect(api.request).toHaveBeenCalledWith(draft, 20)
  expect(container.innerHTML).not.toContain('HIDDEN-RAW-VALUE')
})

test.each(['0', '21', '1.5', ''])(
  '잘못된 행 수 %s는 요청하지 않는다',
  async (limit) => {
    const api = service()
    render(<MappingPreview access={api} prepareDraft={async () => draft} />)
    const user = userEvent.setup()
    const input = screen.getByRole('spinbutton')
    await user.clear(input)
    if (limit) await user.type(input, limit)
    expect(requestButton()).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent('1~20')
    expect(api.request).not.toHaveBeenCalled()
  },
)

test('초안 검증 실패는 요청하지 않고 안내한다', async () => {
  const api = service()
  render(
    <MappingPreview
      access={api}
      prepareDraft={async () => {
        throw new PreviewDraftError('키 컬럼을 선택해 주세요.')
      }}
    />,
  )
  await userEvent.click(requestButton())
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '키 컬럼을 선택해 주세요.',
  )
  expect(api.request).not.toHaveBeenCalled()
})

test('중복 접수를 막고 joined·대기·실행·완료를 표시한다', async () => {
  const api = service()
  let accept!: (value: MappingPreviewReceipt) => void
  api.request.mockImplementation(
    () =>
      new Promise((resolve) => {
        accept = resolve
      }),
  )
  api.read
    .mockResolvedValueOnce({ ...output(), status: 'QUEUED' })
    .mockResolvedValueOnce({ ...output(), status: 'RUNNING' })
    .mockResolvedValue(output())
  render(<MappingPreview access={api} prepareDraft={async () => draft} />)
  await userEvent.dblClick(requestButton())
  expect(api.request).toHaveBeenCalledTimes(1)
  expect(requestButton()).toBeDisabled()
  await act(async () => accept({ ...receipt(), joined: true }))
  await screen.findByText('변환 미리보기 대기 중…')
  expect(screen.getByText(/기존 진행 중인 요청/)).toBeInTheDocument()
  await screen.findByText('변환 미리보기 실행 중…')
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  await screen.findByRole('table')
  expect(api.read).toHaveBeenCalledTimes(3)
})

test.each(['forbidden', 'conflict', 'server'] as const)(
  '요청 오류 %s 원문은 숨기고 상태를 구분한다',
  async (kind) => {
    const api = service()
    api.request.mockRejectedValue(
      new ApiError('PRIVATE-ROW-SECRET', {
        kind,
        status: kind === 'forbidden' ? 403 : kind === 'conflict' ? 409 : 500,
      }),
    )
    const { container } = render(
      <MappingPreview access={api} prepareDraft={async () => draft} />,
    )
    await userEvent.click(requestButton())
    await screen.findByRole('alert')
    expect(container.innerHTML).not.toContain('PRIVATE-ROW-SECRET')
    expect(api.read).not.toHaveBeenCalled()
  },
)

test('결과 조회 실패 시 접수하지 않고 조회만 재시도한다', async () => {
  const api = service()
  api.read
    .mockRejectedValueOnce(new Error('PRIVATE-ROW-SECRET'))
    .mockResolvedValue(output())
  render(<MappingPreview access={api} prepareDraft={async () => draft} />)
  await userEvent.click(requestButton())
  await screen.findByRole('alert')
  expect(requestButton()).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  await screen.findByRole('table')
  expect(api.request).toHaveBeenCalledTimes(1)
})

test.each(['empty', 'failed', 'missing'] as const)(
  '빈 결과·작업 실패·404 %s를 표시하고 재요청한다',
  async (kind) => {
    const api = service()
    if (kind === 'missing')
      api.read
        .mockRejectedValueOnce(
          new ApiError('gone', { kind: 'not-found', status: 404 }),
        )
        .mockResolvedValue(output())
    else
      api.read.mockResolvedValue({
        status: kind === 'failed' ? 'FAILED' : 'SUCCEEDED',
        rows: [],
        rowErrors: [],
      })
    render(<MappingPreview access={api} prepareDraft={async () => draft} />)
    await userEvent.click(requestButton())
    await screen.findByText(
      kind === 'empty'
        ? '변환된 객체가 없습니다.'
        : kind === 'failed'
          ? /시험 변환 작업에 실패/
          : /미리보기가 만료되었거나/,
    )
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(requestButton()).toBeEnabled()
    if (kind === 'missing') {
      await userEvent.click(requestButton())
      await screen.findByRole('table')
      expect(api.request).toHaveBeenCalledTimes(2)
    }
  },
)

test('만료 시 결과를 지우고 이미 만료된 접수는 조회하지 않는다', async () => {
  const api = service()
  api.request
    .mockImplementationOnce(async () => ({
      ...receipt(),
      expiresAt: new Date(Date.now() + 1000).toISOString(),
    }))
    .mockImplementationOnce(async () => ({
      ...receipt(),
      expiresAt: new Date(Date.now() - 1000).toISOString(),
    }))
  render(<MappingPreview access={api} prepareDraft={async () => draft} />)
  await userEvent.click(requestButton())
  await screen.findByRole('table')
  await screen.findByText(/미리보기가 만료되었거나/)
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  await userEvent.click(requestButton())
  await screen.findByText(/미리보기가 만료되었거나/)
  expect(api.read).toHaveBeenCalledTimes(1)
})

test('초안 확인 중 창을 닫으면 뒤늦게 미리보기를 접수하지 않는다', async () => {
  const api = service()
  let resolve!: (value: MappingPreviewDraft) => void
  const { unmount } = render(
    <MappingPreview
      access={api}
      prepareDraft={() =>
        new Promise((done) => {
          resolve = done
        })
      }
    />,
  )
  await userEvent.click(requestButton())
  unmount()
  await act(async () => resolve(draft))
  expect(api.request).not.toHaveBeenCalled()
})

test('초안 변경 후 이전 결과가 도착해도 표시하지 않는다', async () => {
  const api = service()
  let finish!: (value: MappingPreviewResult) => void
  api.read.mockImplementationOnce(
    () =>
      new Promise((done) => {
        finish = done
      }),
  )
  const prepare = async () => draft
  const { rerender } = render(
    <MappingPreview key="old" access={api} prepareDraft={prepare} />,
  )
  await userEvent.click(requestButton())
  rerender(<MappingPreview key="new" access={api} prepareDraft={prepare} />)
  await act(async () => finish(output()))
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByText(/미리보기 #51/)).not.toBeInTheDocument()
})
