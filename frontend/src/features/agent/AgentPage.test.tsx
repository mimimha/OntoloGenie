import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test } from 'vitest'
import { resetAgentMocks } from '../../mocks/agentHandlers'
import { server } from '../../mocks/node'
import AgentPage from './AgentPage'
import type { AgentAction } from './types'

afterEach(() => {
  resetAgentMocks()
})

function renderAgentPage(path = '/agent') {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/agent" element={<AgentPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const SAMPLE_ANSWER = /PR-03의 이번 달 비가동은 2건입니다/
const EMPTY_MESSAGE = '업무에 대해 질문해 주세요. 예: PR-03의 상태를 알려줘'

test('대화 목록을 최근 순으로 보여 주고 가장 최근 대화를 연다', async () => {
  renderAgentPage()

  const list = await screen.findByRole('complementary', { name: '대화방 목록' })
  const items = within(list).getAllByRole('listitem')
  expect(items[0]).toHaveTextContent('새 대화')
  expect(items[1]).toHaveTextContent(
    '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
  )
  expect(
    within(list).getByRole('button', { name: '새 대화', pressed: true }),
  ).toBeInTheDocument()
  expect(await screen.findByText(EMPTY_MESSAGE)).toBeInTheDocument()
})

test('답변의 정보별 결과와 기준 시점을 구분해 보여 준다', async () => {
  renderAgentPage('/agent?conversation=1')

  expect(await screen.findByText(SAMPLE_ANSWER)).toBeInTheDocument()
  // 정보별 결과는 정보 하나가 한 줄인 표다(머리줄 + 4줄).
  expect(
    within(screen.getByRole('table', { name: '정보별 결과' })).getAllByRole(
      'row',
    ),
  ).toHaveLength(5)
  expect(screen.getByText('확인됨')).toBeInTheDocument()
  expect(screen.getByText('확인됨 · 추정')).toBeInTheDocument()
  expect(screen.getByText('권한 없음')).toBeInTheDocument()
  expect(screen.getByText('확인하지 못함')).toBeInTheDocument()
  expect(screen.getAllByText('기준 시점 확인 불가')).toHaveLength(2)
  expect(screen.getByText('최신 데이터 반영 중')).toBeInTheDocument()
})

test('질문을 보내면 처리 단계를 보여 주다가 답변으로 바꾼다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=2')
  await screen.findByText(EMPTY_MESSAGE)

  const input = screen.getByLabelText('업무 질문')
  await user.type(input, 'PR-03 비가동 알려줘')
  await user.click(screen.getByRole('button', { name: '보내기 ↑' }))

  expect(await screen.findByText(/답변 준비 중/)).toBeInTheDocument()
  expect(input).toBeDisabled()
  expect(
    await screen.findByText(SAMPLE_ANSWER, {}, { timeout: 10_000 }),
  ).toBeInTheDocument()
  expect(input).toBeEnabled()
  const list = screen.getByRole('complementary', { name: '대화방 목록' })
  await waitFor(() =>
    expect(
      within(list).getByRole('button', { name: 'PR-03 비가동 알려줘' }),
    ).toBeInTheDocument(),
  )
}, 15_000)

test('답변을 끝내지 못하면 실패 안내를 보여 준다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=2')
  await screen.findByText(EMPTY_MESSAGE)

  await user.type(screen.getByLabelText('업무 질문'), '실패 확인용 질문')
  await user.click(screen.getByRole('button', { name: '보내기 ↑' }))

  const alert = await screen.findByRole('alert', {}, { timeout: 10_000 })
  expect(alert).toHaveTextContent('답변을 끝내지 못했어요.')
  expect(alert).toHaveTextContent('LLM 응답 시간 초과')
}, 15_000)

test('새 대화를 만들면 목록에 추가하고 그 대화를 연다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=1')
  const list = await screen.findByRole('complementary', { name: '대화방 목록' })

  await user.click(
    await within(list).findByRole('button', { name: '+ 새 대화' }),
  )

  await waitFor(() =>
    expect(within(list).getAllByRole('listitem')).toHaveLength(3),
  )
  // 목록이 먼저 늘고 선택 표시는 그 뒤에 바뀐다. 바쁠 때는 바로 찾으면 아직 없어 실패한다
  expect(
    await within(list).findByRole('button', { name: '새 대화', pressed: true }),
  ).toBeInTheDocument()
})

test('객체에서 들어오면 질문을 미리 채우고 첫 질문에 그 객체를 함께 보낸다', async () => {
  const user = userEvent.setup()
  const bodies: unknown[] = []
  const recordBody = ({ request }: { request: Request }) => {
    if (
      request.method === 'POST' &&
      request.url.endsWith('/agent/conversations/2/messages')
    ) {
      void request
        .clone()
        .json()
        .then((body) => bodies.push(body))
    }
  }
  server.events.on('request:start', recordBody)
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter
        initialEntries={[
          {
            pathname: '/agent',
            search: '?conversation=2',
            state: {
              conversationId: 2,
              referenceObjectId: 301,
              draft: '프레스 3호기에 연결된 업무 정보를 알려줘',
            },
          },
        ]}
      >
        <Routes>
          <Route path="/agent" element={<AgentPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )

  expect(await screen.findByLabelText('업무 질문')).toHaveValue(
    '프레스 3호기에 연결된 업무 정보를 알려줘',
  )
  await user.click(screen.getByRole('button', { name: '보내기 ↑' }))

  await waitFor(() =>
    expect(bodies).toEqual([
      {
        content: '프레스 3호기에 연결된 업무 정보를 알려줘',
        referenceObjectIds: [301],
      },
    ]),
  )
  server.events.removeListener('request:start', recordBody)
})

test('답변의 정보별 결과에서 출처 객체와 실행 이력으로 이동할 수 있다', async () => {
  server.use(
    http.get('/api/v1/agent/conversations/:id/messages', () =>
      HttpResponse.json({
        data: [
          {
            id: 900,
            conversationId: 1,
            sender: 'AGENT',
            content: '프레스 3호기의 최근 실행 이력은 1건입니다.',
            context: {},
            status: 'SUCCEEDED',
            errorMessage: null,
            answerMeta: {
              syncStatus: 'NONE',
              results: [
                {
                  label: '실행 이력',
                  toolCode: 'OBJECT_EXECUTIONS',
                  status: 'SUCCEEDED',
                  resultCategory: 'FOUND',
                  sources: [{ objectId: 301 }, { objectId: 301 }],
                  dataAsOf: null,
                  estimated: false,
                  errorMessage: null,
                },
              ],
            },
            createdAt: '2026-11-06T01:00:00Z',
          },
        ],
        meta: { page: 0, size: 100, totalElements: 1, hasNext: false },
      }),
    ),
  )
  renderAgentPage('/agent?conversation=1')

  expect(
    await screen.findByRole('link', { name: '#301 열기 ↗' }),
  ).toHaveAttribute('href', '/explorer?object=301')
  expect(screen.getByRole('link', { name: '실행 이력 ↗' })).toHaveAttribute(
    'href',
    '/audit?objectId=301',
  )
  expect(
    screen.getByText('응답 시점의 값 · 이후 수정은 다시 질문해 확인'),
  ).toBeInTheDocument()
})

test('실행 이력 도구가 아닌 결과에는 실행 이력 링크를 달지 않는다', async () => {
  renderAgentPage('/agent?conversation=1')

  expect(
    await screen.findByRole('link', { name: '#4101 열기 ↗' }),
  ).toHaveAttribute('href', '/explorer?object=4101')
  expect(
    screen.queryByRole('link', { name: '실행 이력 ↗' }),
  ).not.toBeInTheDocument()
})

// 대화 1에 Agent 답변 하나만 있고, 그 답변이 실행한 작업이 actions인 상태로 바꾼다(160).
// failed면 Action을 실행한 뒤 답변을 끝내지 못한 경우다.
function mockAnswerWithActions(actions: AgentAction[], failed = false) {
  server.use(
    http.get('/api/v1/agent/conversations/:id/messages', () =>
      HttpResponse.json({
        data: [
          {
            id: 901,
            conversationId: 1,
            sender: 'AGENT',
            content: failed
              ? '답변을 끝내지 못했습니다. 잠시 후 다시 질문해 주세요.'
              : '재배정 계획 요청을 처리했습니다.',
            context: {},
            status: failed ? 'FAILED' : 'SUCCEEDED',
            errorMessage: failed ? 'AI 응답 실패' : null,
            answerMeta: { syncStatus: 'NONE', results: [], actions },
            createdAt: '2026-11-09T01:00:00Z',
          },
        ],
        meta: { page: 0, size: 100, totalElements: 1, hasNext: false },
      }),
    ),
  )
}

test('재배정 계획을 만든 답변 안에서 계획을 펼치고 접는다', async () => {
  const user = userEvent.setup()
  mockAnswerWithActions([
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'SUCCEEDED',
      executionId: 512,
      result: { planId: 3, itemCount: 1 },
      blockedReason: null,
    },
  ])
  renderAgentPage('/agent?conversation=1')

  expect(
    await screen.findByText('계획 #3 · 실제 MES 배정은 바뀌지 않아요'),
  ).toBeInTheDocument()
  // 펼치기 전에는 계획을 불러오지 않는다.
  expect(
    screen.queryByRole('region', { name: '재배정 계획 #3' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '계획 펼치기' }))

  const plan = await screen.findByRole('region', { name: '재배정 계획 #3' })
  expect(await within(plan).findByText('WO-101')).toBeInTheDocument()
  expect(within(plan).getByText('남은 공정 전체')).toBeInTheDocument()
  expect(
    within(plan).getByText('같은 프레스 공정이고 지금 가동 중인 설비'),
  ).toBeInTheDocument()
  expect(within(plan).getByRole('link', { name: '#302' })).toHaveAttribute(
    'href',
    '/explorer?object=302',
  )

  await user.click(screen.getByRole('button', { name: '계획 접기' }))
  expect(
    screen.queryByRole('region', { name: '재배정 계획 #3' }),
  ).not.toBeInTheDocument()
})

test('막힌 작업은 이유를 보여 주고 계획 펼치기를 달지 않는다', async () => {
  mockAnswerWithActions([
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'BLOCKED',
      executionId: 513,
      result: {},
      blockedReason: 'PLAN_CREATE 권한이 없습니다.',
    },
  ])
  renderAgentPage('/agent?conversation=1')

  expect(
    await screen.findByText('PLAN_CREATE 권한이 없습니다.'),
  ).toBeInTheDocument()
  expect(screen.getByText('실행하지 않음')).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '계획 펼치기' }),
  ).not.toBeInTheDocument()
})

test('답변이 실패해도 그 전에 만든 계획은 보여 준다', async () => {
  mockAnswerWithActions(
    [
      {
        code: 'CREATE_REASSIGNMENT_PLAN',
        name: '재배정 계획 생성',
        status: 'SUCCEEDED',
        executionId: 512,
        result: { planId: 3, itemCount: 1 },
        blockedReason: null,
      },
    ],
    true,
  )
  renderAgentPage('/agent?conversation=1')

  expect(await screen.findByRole('alert')).toHaveTextContent('AI 응답 실패')
  // 계획은 이미 저장됐으므로 다시 요청하지 않도록 계획을 보여 준다.
  expect(
    screen.getByText('계획 #3 · 실제 MES 배정은 바뀌지 않아요'),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '계획 펼치기' }),
  ).toBeInTheDocument()
})

test('실패·실행하지 못함·확인 필요는 성공처럼 보이지 않는다', async () => {
  mockAnswerWithActions([
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'FAILED',
      executionId: 514,
      result: { errorMessage: '실행 중 오류가 발생했습니다.' },
      blockedReason: null,
    },
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'NOT_RUN',
      executionId: null,
      result: {},
      blockedReason: null,
    },
    {
      code: 'CLOSE_ORDER',
      name: '작업지시 종료',
      status: 'CONFIRMATION_REQUIRED',
      executionId: null,
      result: {},
      blockedReason: null,
    },
  ])
  renderAgentPage('/agent?conversation=1')

  const list = await screen.findByRole('list', { name: '실행한 작업' })
  expect(within(list).getByText('실패')).toBeInTheDocument()
  expect(
    within(list).getByText('실행 중 오류가 발생했습니다.'),
  ).toBeInTheDocument()
  expect(within(list).getByText('실행하지 못함')).toBeInTheDocument()
  expect(
    within(list).getByText('확인이 필요해 실행하지 않음'),
  ).toBeInTheDocument()
  expect(within(list).queryByText('완료')).not.toBeInTheDocument()
  expect(
    within(list).queryByRole('button', { name: '계획 펼치기' }),
  ).not.toBeInTheDocument()
})

test('계획을 불러오지 못하면 오류를 보여 준다', async () => {
  const user = userEvent.setup()
  mockAnswerWithActions([
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'SUCCEEDED',
      executionId: 515,
      // 목 서버에 없는 계획 번호라 094가 404를 돌려준다.
      result: { planId: 99, itemCount: 1 },
      blockedReason: null,
    },
  ])
  renderAgentPage('/agent?conversation=1')

  await user.click(await screen.findByRole('button', { name: '계획 펼치기' }))

  const plan = screen.getByRole('region', { name: '재배정 계획 #99' })
  expect(
    await within(plan).findByText('요청한 항목을 찾을 수 없습니다.'),
  ).toBeInTheDocument()
})
