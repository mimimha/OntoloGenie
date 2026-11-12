import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import { fetchAuditExecutions, fetchObjectExecutions } from './api'
import type { ExecutionFilters } from './api'

test('080은 기간·상태·페이지를 전송하고 096 전용 조건은 보내지 않는다', async () => {
  let received: URL | undefined
  server.use(
    http.get('/api/v1/objects/:id/executions', ({ request }) => {
      received = new URL(request.url)
      return HttpResponse.json({
        data: [],
        meta: { page: 2, size: 20, totalElements: 41, hasNext: false },
      })
    }),
  )
  const filters: ExecutionFilters = {
    from: '2026-11-10T15:00:00.000Z',
    to: '2026-11-11T15:00:00.000Z',
    status: 'BLOCKED',
    category: 'CHANGE',
    targetType: 'proposal_item',
    targetId: 33,
  }

  const result = await fetchObjectExecutions(301, filters, 2)

  expect(received?.pathname).toBe('/api/v1/objects/301/executions')
  expect(Object.fromEntries(received!.searchParams)).toEqual({
    from: filters.from,
    to: filters.to,
    status: 'BLOCKED',
    page: '2',
    size: '20',
  })
  expect(result.meta).toEqual({
    page: 2,
    size: 20,
    totalElements: 41,
    hasNext: false,
  })
})

test('080은 기본 첫 페이지를 조회하고 서버가 허용한 기록만 반환한다', async () => {
  setMockAccount({ ...demoAccount, id: 99 })
  const mine = await fetchObjectExecutions(301, {})
  expect(mine.data).toEqual([])
  expect(mine.meta).toMatchObject({ page: 0, totalElements: 0 })

  setMockAccount({ ...demoAccount, id: 99, roleCode: 'ADMIN' })
  const all = await fetchObjectExecutions(301, {})
  expect(all.data).toHaveLength(3)
  expect(all.meta).toMatchObject({ page: 0, size: 20, totalElements: 3 })
})

test('080은 로그인·비밀번호 변경 제한·없는 객체 오류를 유지한다', async () => {
  setMockAccount(null)
  await expect(fetchObjectExecutions(301, {})).rejects.toMatchObject({
    status: 401,
    code: 'UNAUTHENTICATED',
  })
  setMockAccount({ ...demoAccount, mustChangePassword: true })
  await expect(fetchObjectExecutions(301, {})).rejects.toMatchObject({
    status: 403,
    code: 'FORBIDDEN',
  })
  setMockAccount({ ...demoAccount })
  await expect(fetchObjectExecutions(999999, {})).rejects.toMatchObject({
    status: 404,
    code: 'RESOURCE_NOT_FOUND',
  })
})

test('080 조회는 화면이 떠나면 취소할 수 있다', async () => {
  const controller = new AbortController()
  controller.abort()
  await expect(
    fetchObjectExecutions(301, {}, 0, { signal: controller.signal }),
  ).rejects.toMatchObject({ kind: 'aborted' })
})

test('096은 공용 이력 화면의 페이지와 종류·대상 조건을 함께 보낸다', async () => {
  let received: URL | undefined
  server.use(
    http.get('/api/v1/audit/actions', ({ request }) => {
      received = new URL(request.url)
      return HttpResponse.json({ data: [] })
    }),
  )
  await fetchAuditExecutions(
    { category: 'CHANGE', targetType: 'proposal_item', targetId: 33 },
    1,
  )
  expect(Object.fromEntries(received!.searchParams)).toEqual({
    category: 'CHANGE',
    targetType: 'proposal_item',
    targetId: '33',
    page: '1',
    size: '20',
  })
})
