import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import { fetchConversations } from './api'

test('첫 50개 이후의 대화도 다음 페이지에서 읽어 순서대로 반환한다', async () => {
  const pages: number[] = []
  const conversations = Array.from({ length: 51 }, (_, index) => ({
    id: 51 - index,
    title: `대화 ${51 - index}`,
    startedAt: '2026-11-12T00:00:00Z',
    lastMessageAt: '2026-11-12T00:00:00Z',
  }))
  server.use(
    http.get('/api/v1/agent/conversations', ({ request }) => {
      const params = new URL(request.url).searchParams
      const page = Number(params.get('page'))
      const size = Number(params.get('size'))
      pages.push(page)
      return HttpResponse.json({
        data: conversations.slice(page * size, (page + 1) * size),
        meta: { page, size, totalElements: 51, hasNext: page === 0 },
      })
    }),
  )
  expect(await fetchConversations()).toEqual(conversations)
  expect(pages).toEqual([0, 1])
})

test('뒤 페이지 조회 실패 시 불완전한 목록을 성공으로 반환하지 않는다', async () => {
  server.use(
    http.get('/api/v1/agent/conversations', ({ request }) => {
      if (new URL(request.url).searchParams.get('page') === '1') {
        return HttpResponse.json(
          { error: { code: 'INTERNAL_ERROR', message: '목록 조회 실패' } },
          { status: 500 },
        )
      }
      return HttpResponse.json({
        data: [{ id: 1, title: '대화 1' }],
        meta: { page: 0, size: 50, totalElements: 51, hasNext: true },
      })
    }),
  )
  await expect(fetchConversations()).rejects.toThrow('목록 조회 실패')
})
