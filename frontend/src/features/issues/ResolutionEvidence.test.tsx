import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { expect, test } from 'vitest'
import { issueFixture } from '../../mocks/issueHandlers'
import { server } from '../../mocks/node'
import { fetchResolutionRuleId } from './api'
import IssueReverification from './IssueReverification'
import ReverificationHistory from './ReverificationHistory'

const record = (checkRunId = 42, linkMappingId: unknown = 24) => ({
  issueId: 1,
  checkRunId,
  triggerKind: 'REVERIFY',
  status: 'SUCCEEDED',
  outcome: 'PASSED',
  detail: { linkMappingId },
  checkedAt: '2026-11-12T01:00:00Z',
})
const meta = { page: 0, size: 20, totalElements: 1, hasNext: false }
function wrapper(content: React.ReactNode) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>{content}</MemoryRouter>
    </QueryClientProvider>,
  )
}
function mockComparison(hasBaselinePassEvidence = false, coverage = 'PARTIAL') {
  server.use(
    http.get('/api/v1/checks/runs/42/comparison', () =>
      HttpResponse.json({
        data: {
          comparedToRunId: 1,
          coverage,
          hasBaselinePassEvidence,
          rows: hasBaselinePassEvidence
            ? [
                {
                  category: 'NEW',
                  ruleCode: 'ORDER_CUSTOMER',
                  objectInstanceId: 9,
                  before: { classification: 'MATCHED' },
                  after: { classification: 'UNMATCHED' },
                },
              ]
            : [],
        },
        meta: { ...meta, totalElements: hasBaselinePassEvidence ? 1 : 0 },
      }),
    ),
  )
}

test('URL의 재검증 정보 없이 다시 열어도 해결 실행의 실제 규칙 개정본으로 조회한다', async () => {
  const requests: { run: string; rule: string | null }[] = []
  const pages: number[] = []
  server.use(
    http.get('/api/v1/issues/1/verifications', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      pages.push(page)
      return HttpResponse.json({
        data: [page === 0 ? record(99, 99) : record()],
        meta: { page, size: 1, totalElements: 2, hasNext: page === 0 },
      })
    }),
    http.get('/api/v1/checks/runs/:id/results', ({ params, request }) => {
      const rule = new URL(request.url).searchParams.get('linkMappingId')
      requests.push({ run: String(params.id), rule })
      return HttpResponse.json({
        data: [
          {
            id: Number(params.id),
            checkRunId: Number(params.id),
            linkMappingId: Number(rule),
            proposalItemId: null,
            proposalItemRevision: null,
            previousResultId: null,
            totalCount: 1,
            matchedCount: 1,
            unmatchedCount: 0,
            duplicateCount: 0,
            violationCount: 0,
            matchRate: 1,
            sampleRows: [
              {
                rawValue:
                  params.id === '1' ? '발견 규칙 근거' : '해결 규칙 근거',
              },
            ],
          },
        ],
        meta,
      })
    }),
  )
  mockComparison()
  wrapper(
    <IssueReverification
      issue={{ ...issueFixture, status: 'RESOLVED', resolvedCheckRunId: 42 }}
    />,
  )
  await userEvent.setup().click(screen.getByText('검사 결과 비교'))
  expect(await screen.findByText('발견 규칙 근거')).toBeInTheDocument()
  const resolved = within(
    screen.getByRole('region', { name: '해결 처리에 사용한 재검증 결과' }),
  )
  expect(await resolved.findByText('해결 규칙 근거')).toBeInTheDocument()
  expect(requests).toEqual(
    expect.arrayContaining([
      { run: '1', rule: '12' },
      { run: '42', rule: '24' },
    ]),
  )
  expect(requests).toHaveLength(2)
  expect(pages).toContain(1)
})

test.each([undefined, null, 0, -1, '24'])(
  '해결 규칙 정보 %s가 없거나 잘못되면 발견 당시 규칙으로 대신 조회하지 않는다',
  async (ruleId) => {
    const results: string[] = []
    server.use(
      http.get('/api/v1/issues/1/verifications', () =>
        HttpResponse.json({
          data: [{ ...record(), detail: { linkMappingId: ruleId } }],
          meta,
        }),
      ),
      http.get('/api/v1/checks/runs/:id/results', ({ params }) => {
        results.push(String(params.id))
        return HttpResponse.json({
          data: [],
          meta: { ...meta, totalElements: 0 },
        })
      }),
    )
    mockComparison()
    wrapper(
      <IssueReverification
        issue={{ ...issueFixture, status: 'RESOLVED', resolvedCheckRunId: 42 }}
      />,
    )
    await userEvent.setup().click(screen.getByText('검사 결과 비교'))
    const resolved = within(
      screen.getByRole('region', { name: '해결 처리에 사용한 재검증 결과' }),
    )
    expect(
      await resolved.findByText(
        '해결에 사용한 규칙 정보를 확인할 수 없습니다.',
      ),
    ).toBeInTheDocument()
    expect(results).toEqual(['1'])
  },
)

test('해결 실행의 판정 기록이 없으면 최신 실행의 규칙을 사용하지 않는다', async () => {
  server.use(
    http.get('/api/v1/issues/1/verifications', () =>
      HttpResponse.json({ data: [record(99, 99)], meta }),
    ),
  )
  expect(await fetchResolutionRuleId(1, 42)).toBeNull()
})

test.each([
  { ...record(), issueId: 2 },
  { ...record(), outcome: 'FAILED' },
  { ...record(), status: 'FAILED' },
  { ...record(), triggerKind: 'MANUAL' },
])(
  '다른 문제·실패·비재검증 판정은 해결 근거로 사용할 수 없다 (%j)',
  async (invalid) => {
    server.use(
      http.get('/api/v1/issues/1/verifications', () =>
        HttpResponse.json({ data: [invalid], meta }),
      ),
    )
    await expect(fetchResolutionRuleId(1, 42)).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  },
)

test.each([401, 403, 404, 500])(
  '해결 판정 조회의 %i 오류를 유지한다',
  async (status) => {
    server.use(
      http.get('/api/v1/issues/1/verifications', () =>
        HttpResponse.json(
          {
            error: {
              code: 'ERROR',
              message: '판정 조회 실패',
              details: {},
              requestId: 'test',
            },
          },
          { status },
        ),
      ),
    )
    await expect(fetchResolutionRuleId(1, 42)).rejects.toMatchObject({
      status,
      message: '판정 조회 실패',
    })
  },
)

test.each([{}, { ...meta, page: 2 }, { ...meta, hasNext: true }])(
  '판정 페이지가 잘못되면 무한 조회하지 않는다 (%j)',
  async (invalidMeta) => {
    server.use(
      http.get('/api/v1/issues/1/verifications', () =>
        HttpResponse.json({ data: [], meta: invalidMeta }),
      ),
    )
    await expect(fetchResolutionRuleId(1, 42)).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  },
)

test.each([false, true])(
  '이전 정상 판정 유무 %s와 관계없이 기록이 없는 항목의 신규 여부는 단정하지 않는다',
  async (hasBaselinePassEvidence) => {
    mockComparison(hasBaselinePassEvidence)
    server.use(
      http.get('/api/v1/issues/1/verifications', () =>
        HttpResponse.json({ data: [record()], meta }),
      ),
    )
    wrapper(<ReverificationHistory issueId={1} resolvedRunId={42} />)
    expect(
      await screen.findByText(
        /이전 판정 기록이 없는 항목은 새로 생긴 문제인지 알 수 없어요/,
      ),
    ).toBeInTheDocument()
    if (hasBaselinePassEvidence) {
      expect(
        screen.getByRole('region', { name: '신규 문제' }),
      ).toHaveTextContent('이전 · 정상')
      expect(
        screen.getByRole('region', { name: '신규 문제' }),
      ).toHaveTextContent('이번 · 미연결')
    } else {
      expect(
        screen.getByRole('heading', { name: '신규 문제 확인 불가' }),
      ).toBeInTheDocument()
    }
  },
)
