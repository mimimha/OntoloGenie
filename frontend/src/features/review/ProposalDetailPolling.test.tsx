import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import { CHECK_POLL_LIMIT, CHECK_POLL_MS } from './checkPolling'
import ProposalDetail from './ProposalDetail'
import type { Proposal } from './types'

afterEach(() => {
  vi.useRealTimers()
})

const waiting: Proposal = {
  id: 901,
  analysisRunId: 2,
  origin: 'AI',
  itemKind: 'PROPERTY',
  revision: 1,
  payload: {
    code: 'so_ref',
    name: '주문 참조 번호',
    objectTypeCode: 'WORK_ORDER',
  },
  evidence: {
    candidateId: 'P-mes.work_order.so_ref',
    fingerprint: '8b1a72e5df747524',
    title: '속성 WORK_ORDER.so_ref',
    method: 'rule',
    reason: '자료형 STRING(선언 varchar(20)), 빈 값 3행',
    confidenceItems: [
      { group: '값 비교', label: '선언된 자료형', weight: 0.35 },
    ],
    confidenceCap: null,
    llmJudgement: null,
    holdRecommended: true,
    holdReason: '번호가 우연히 같을 수 있음',
    items: [
      {
        sourceId: 'MES_MAIN',
        locator: 'mes.work_order.so_ref',
        snippet: '값 136/139, 서로 다른 값 96',
        values: ['2607001'],
        verified: true,
      },
    ],
  },
  aiConfidence: 0.497,
  reviewStatus: 'PENDING',
  applyStatus: 'NOT_APPLIED',
  applyError: null,
  appliedAt: null,
  latestCheck: {
    checkRunId: 105,
    status: 'QUEUED',
    matchedCount: null,
    totalCount: null,
    matchRate: null,
  },
}

function open() {
  let calls = 0
  server.use(
    http.get('/api/v1/proposals/:id', () => {
      calls += 1
      return HttpResponse.json({ data: waiting })
    }),
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={['/admin/rules/901']}>
        <Routes>
          <Route path="/admin/rules/:id" element={<ProposalDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return () => calls
}

test('제안 근거에서 내부 값은 숨기고 원천 근거를 목록으로 보여 준다', async () => {
  open()

  const list = await screen.findByRole('list', { name: '원천 근거' })
  expect(list).toHaveTextContent('MES_MAIN · mes.work_order.so_ref')
  expect(list).toHaveTextContent('값 136/139, 서로 다른 값 96')
  expect(list).toHaveTextContent('원천에서 다시 확인함')
  expect(
    screen.getByText('자료형 STRING(선언 varchar(20)), 빈 값 3행'),
  ).toBeInTheDocument()
  expect(screen.getByText('번호가 우연히 같을 수 있음')).toBeInTheDocument()
  for (const internal of [
    'candidateId',
    'fingerprint',
    'confidenceItems',
    'confidenceCap',
    'llmJudgement',
    '8b1a72e5df747524',
  ])
    expect(screen.queryByText(internal)).not.toBeInTheDocument()
})

test('검사 대기 중 자동 확인은 입력을 막지 않고 정해진 횟수 뒤 멈춘다', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const calls = open()
  const reason = await screen.findByRole('textbox', {
    name: '검토 사유 (필수)',
  })
  const check = screen.getByRole('region', { name: '정합성 검사' })
  expect(check).toHaveTextContent('3초마다 상태를 확인합니다')

  for (let i = 0; i < CHECK_POLL_LIMIT + 3; i++) {
    await vi.advanceTimersByTimeAsync(CHECK_POLL_MS)
    // 백그라운드 확인 중에도 입력 칸과 새로고침 버튼은 그대로다(깜박이지 않음)
    expect(reason).not.toBeDisabled()
    expect(
      screen.queryByText(/최신 제안 상태를 확인 중이거나/),
    ).not.toBeInTheDocument()
  }

  expect(calls()).toBe(CHECK_POLL_LIMIT)
  expect(within(check).getByText(/자동 확인을 멈췄습니다/)).toBeInTheDocument()
  expect(check).not.toHaveTextContent('3초마다 상태를 확인합니다')
})

test('보류 권고만 있는 근거도 보류 이유를 보여 주고 모양이 다른 값은 숨기지 않는다', async () => {
  waiting.evidence = {
    holdRecommended: true,
    holdReason: '대상이 여러 개라 사람이 골라야 함',
    llmJudgement: { verdict: 'unclear' },
  }
  open()

  expect(
    await screen.findByText('대상이 여러 개라 사람이 골라야 함'),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('정보가 제공되지 않았습니다.'),
  ).not.toBeInTheDocument()
  expect(screen.getByText(/"verdict": "unclear"/)).toBeInTheDocument()
})

test('제안 내용은 칸 이름·코드 값을 우리말로 보여 주고 빈 칸은 숨긴다', async () => {
  waiting.payload = {
    code: 'WORKER',
    name: '현장 작업자',
    note: null,
    category: 'master',
    synonyms: [],
    description: '현장 작업자별 번호와 이름을 관리하는 기준 정보입니다.',
  }
  open()

  const content = (await screen.findByText('제안 내용')).closest('section')!
  expect(within(content).getByText('표 종류')).toBeInTheDocument()
  expect(within(content).getByText('기준 정보')).toBeInTheDocument()
  expect(within(content).queryByText('category')).not.toBeInTheDocument()
  expect(within(content).queryByText('master')).not.toBeInTheDocument()
  expect(within(content).queryByText('메모')).not.toBeInTheDocument()
  expect(within(content).queryByText('다른 이름')).not.toBeInTheDocument()
  expect(content).not.toHaveTextContent('제공되지 않음')
})

test('문서 연결은 원문·언급 위치·관찰 표현을 정보 추출 결과에만 보여 주고 아래에서 다시 늘어놓지 않는다', async () => {
  waiting.itemKind = 'DOCUMENT_LINK'
  waiting.payload = {
    name: 'HN-260804-D → 설비 P201',
    documentId: 'HN-260804-D',
    spanStart: 7,
    spanEnd: 11,
    mentionText: 'P201',
    targetObjectRef: 'mes.equipment:P201',
  }
  waiting.evidence = {
    reason: "본문 기재 'P201'",
    originalText: '오후 3시쯤 P201 진동이 평소보다 커서 점검 요청함.',
    observations: [
      {
        text: '진동이 평소보다 커서',
        spanStart: 12,
        spanEnd: 23,
        assertion: 'AFFIRMED',
      },
    ],
  }
  open()

  const content = (await screen.findByText('제안 내용')).closest('section')!
  expect(within(content).getByText('언급한 글자')).toBeInTheDocument()
  expect(within(content).queryByText('spanStart')).not.toBeInTheDocument()
  const evidence = screen.getByText('제안 근거').closest('section')!
  expect(within(evidence).queryByText('관찰 표현')).not.toBeInTheDocument()
  expect(evidence).not.toHaveTextContent('"assertion"')
  expect(evidence).not.toHaveTextContent('오후 3시쯤')
})

test('연결 조건은 JSON 대신 한 줄 비교식으로 보여 준다', async () => {
  waiting.itemKind = 'LINK_RULE'
  waiting.payload = {
    code: 'WO_EQUIPMENT_CODE',
    linkTypeId: 2,
    conditions: [
      {
        sourceProperty: 'WORK_ORDER.equipment_code',
        targetProperty: 'EQUIPMENT.code',
        operator: 'EQ',
        sourceTransforms: [{ op: 'UPPER', arg: null }],
        targetTransforms: [],
      },
    ],
  }
  waiting.evidence = { reason: '대상 키: 설비 code (기본키) · 일치율 100%' }
  open()

  const content = (await screen.findByText('제안 내용')).closest('section')!
  expect(
    within(content).getByText(
      'WORK_ORDER.equipment_code → UPPER = EQUIPMENT.code',
    ),
  ).toBeInTheDocument()
  expect(content).not.toHaveTextContent('"sourceProperty"')
})
