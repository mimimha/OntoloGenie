import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import { fetchRuleRevisions } from './api'

test('중간 페이지 조회 실패 시 일부 개정 이력을 전체 결과로 반환하지 않는다', async () => {
  const pages: number[] = []
  server.use(
    http.get('/api/v1/ontology/rules/:code/revisions', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      pages.push(page)
      return page === 0
        ? HttpResponse.json({
            data: [{ id: 1, code: 'WO_EQ', revision: 1, status: 'RETIRED' }],
            meta: { page, size: 20, totalElements: 21, hasNext: true },
          })
        : HttpResponse.json(
            {
              error: {
                code: 'INTERNAL_ERROR',
                message: '다음 페이지 조회 실패',
                details: {},
              },
            },
            { status: 500 },
          )
    }),
  )
  await expect(fetchRuleRevisions('WO_EQ')).rejects.toMatchObject({
    status: 500,
    message: '다음 페이지 조회 실패',
  })
  expect(pages).toEqual([0, 1])
})

test.each([
  undefined,
  { page: 1, size: 20, totalElements: 0, hasNext: false },
  { page: 0, size: 20, totalElements: 21, hasNext: true },
])(
  '누락·불일치 페이지나 빈 다음 페이지는 확인 불가로 처리한다: %j',
  async (meta) => {
    server.use(
      http.get('/api/v1/ontology/rules/:code/revisions', () =>
        HttpResponse.json({ data: [], meta }),
      ),
    )
    await expect(fetchRuleRevisions('WO_EQ')).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  },
)
