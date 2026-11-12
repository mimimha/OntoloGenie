import { delay, http, HttpResponse } from 'msw'
import type {
  ActionDefinition,
  ActionDefinitionChanges,
  ActionDefinitionInput,
} from '../features/actions/types'

// 서버에 배포된 Handler라고 가정하는 이름. 없으면 handlerReady가 false다.
const deployedHandlers = new Set(['CREATE_REASSIGNMENT_PLAN'])

export const actionFixtures: ActionDefinition[] = [
  {
    id: 1,
    code: 'CREATE_REASSIGNMENT_PLAN',
    name: '재배정 계획 생성',
    description:
      '정지 설비의 작업지시·공정을 대체 설비로 옮기는 계획을 저장한다.',
    targetKind: 'OBJECT',
    targetObjectTypeId: null,
    actionMode: 'EXECUTE',
    handlerKey: 'CREATE_REASSIGNMENT_PLAN',
    inputSchema: {
      type: 'object',
      required: ['fromEquipmentId'],
      properties: { fromEquipmentId: { type: 'integer', minimum: 1 } },
    },
    actionKind: 'WRITE',
    requiresConfirmation: true,
    requiredPermission: 'PLAN_CREATE',
    isActive: true,
    handlerReady: true,
    rowVersion: 0,
  },
  {
    id: 2,
    code: 'ESTIMATE_COMPLETION',
    name: '예상 완료 시각 계산',
    description:
      '작업지시의 남은 수량과 사이클 시간으로 예상 완료 시각을 계산한다.',
    targetKind: 'OBJECT',
    targetObjectTypeId: 501,
    actionMode: 'EXECUTE',
    handlerKey: 'ESTIMATE_COMPLETION',
    inputSchema: { type: 'object', properties: {} },
    actionKind: 'READ',
    requiresConfirmation: false,
    requiredPermission: 'PLAN_CREATE',
    isActive: true,
    handlerReady: false,
    rowVersion: 2,
  },
  {
    id: 3,
    code: 'UPDATE_ACCOUNT',
    name: '계정 변경',
    description: '계정의 역할·상태를 바꾼다.',
    targetKind: 'SYSTEM',
    targetObjectTypeId: null,
    actionMode: 'RECORD',
    handlerKey: null,
    inputSchema: { type: 'object' },
    actionKind: 'WRITE',
    requiresConfirmation: false,
    requiredPermission: 'ACCOUNT_MANAGE',
    isActive: true,
    handlerReady: false,
    rowVersion: 0,
  },
]

let definitions = structuredClone(actionFixtures)
export function resetActionMocks() {
  definitions = structuredClone(actionFixtures)
}

function fail(
  status: number,
  code: string,
  message: string,
  details: Record<string, unknown> = {},
) {
  return HttpResponse.json(
    { error: { code, message, details, requestId: 'mock-action' } },
    { status },
  )
}

export const actionHandlers = [
  http.get('/api/v1/actions', async ({ request }) => {
    await delay()
    const params = new URL(request.url).searchParams
    const page = Number(params.get('page') ?? 0)
    const size = Number(params.get('size') ?? 20)
    const active = params.get('isActive')
    const kind = params.get('actionKind')
    const matched = definitions.filter(
      (item) =>
        (active === null || String(item.isActive) === active) &&
        (kind === null || item.actionKind === kind),
    )
    return HttpResponse.json({
      data: matched.slice(page * size, page * size + size),
      meta: {
        page,
        size,
        totalElements: matched.length,
        hasNext: (page + 1) * size < matched.length,
      },
    })
  }),
  http.get('/api/v1/actions/:code', async ({ params }) => {
    await delay()
    const found = definitions.find((item) => item.code === params.code)
    return found
      ? HttpResponse.json({ data: found })
      : fail(404, 'RESOURCE_NOT_FOUND', '요청한 항목을 찾을 수 없습니다.')
  }),
  http.post('/api/v1/actions', async ({ request }) => {
    await delay()
    const body = (await request.json()) as ActionDefinitionInput
    if (definitions.some((item) => item.code === body.code))
      return fail(409, 'CONFLICT', '같은 식별자로 등록된 항목이 있습니다.', {
        field: 'code',
      })
    const created: ActionDefinition = {
      ...body,
      id: Math.max(...definitions.map((item) => item.id)) + 1,
      actionMode: 'EXECUTE',
      handlerReady: deployedHandlers.has(body.handlerKey),
      rowVersion: 0,
    }
    definitions.push(created)
    return HttpResponse.json({ data: created }, { status: 201 })
  }),
  http.patch('/api/v1/actions/:code', async ({ params, request }) => {
    await delay()
    const index = definitions.findIndex((item) => item.code === params.code)
    if (index < 0)
      return fail(404, 'RESOURCE_NOT_FOUND', '요청한 항목을 찾을 수 없습니다.')
    const current = definitions[index]
    const body = (await request.json()) as ActionDefinitionChanges
    if (current.actionMode === 'RECORD')
      return fail(
        422,
        'BUSINESS_RULE_VIOLATION',
        '관리 Action 정의는 바꿀 수 없습니다.',
      )
    if (body.rowVersion !== current.rowVersion)
      return fail(
        409,
        'VERSION_CONFLICT',
        '요청한 버전과 현재 버전이 다릅니다.',
        {
          field: 'rowVersion',
        },
      )
    const { rowVersion: _rowVersion, ...changes } = body
    void _rowVersion
    const updated: ActionDefinition = {
      ...current,
      ...changes,
      rowVersion: current.rowVersion + 1,
    }
    updated.handlerReady =
      updated.handlerKey !== null && deployedHandlers.has(updated.handlerKey)
    definitions[index] = updated
    return HttpResponse.json({ data: updated })
  }),
]
