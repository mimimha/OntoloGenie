import { expect, test } from 'vitest'
import { ApiError } from '../../api/client'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { fetchModelGraph } from './api'

test('042 ADMIN 응답은 객체 수 없이 노드와 전체·활성 연결 건수를 반환한다', async () => {
  setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
  const graph = await fetchModelGraph()
  expect(graph.nodes.length).toBeGreaterThan(0)
  for (const node of graph.nodes) {
    expect(Object.keys(node).sort()).toEqual(['code', 'id', 'name'])
  }
  expect(graph.edges).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ relationCount: 2, activeLinkCount: 1 }),
      expect.objectContaining({ relationCount: 0, activeLinkCount: 0 }),
    ]),
  )
})

test.each(['PLANNER', 'MAINTENANCE', 'VIEWER'] as const)(
  '042는 %s에 403을 반환한다',
  async (roleCode) => {
    setMockAccount({ ...demoAccount, roleCode })
    await expect(fetchModelGraph()).rejects.toMatchObject({
      status: 403,
      code: 'FORBIDDEN',
    })
  },
)

test('042는 로그인하지 않으면 401을 반환한다', async () => {
  setMockAccount(null)
  await expect(fetchModelGraph()).rejects.toBeInstanceOf(ApiError)
  await expect(fetchModelGraph()).rejects.toMatchObject({
    status: 401,
    code: 'UNAUTHENTICATED',
  })
})

test('042는 비밀번호 변경이 필요한 ADMIN에 403을 반환한다', async () => {
  setMockAccount({
    ...demoAccount,
    roleCode: 'ADMIN',
    mustChangePassword: true,
  })
  await expect(fetchModelGraph()).rejects.toMatchObject({ status: 403 })
})
