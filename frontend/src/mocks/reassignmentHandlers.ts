import { delay, http, HttpResponse } from 'msw'
import type { ReassignmentPlan } from '../features/reassignment/types'

// Agent 목 답변이 만든 것으로 보여 주는 재배정 계획. 프레스 3호기(301)의 WO-101을 프레스 1호기(302)로 옮긴다.
export const samplePlan: ReassignmentPlan = {
  id: 3,
  status: 'CREATED',
  fromEquipmentId: 301,
  selectionBasis: { reason: '같은 프레스 공정이고 지금 가동 중인 설비' },
  dataAsOf: '2026-10-27T09:00:00+09:00',
  actionExecutionId: 512,
  agentConversationId: 1,
  createdBy: 7,
  createdAt: '2026-10-27T09:10:02+09:00',
  items: [
    {
      id: 1,
      workOrderId: 1101,
      operationCode: null,
      fromEquipmentId: 301,
      toEquipmentId: 302,
      sourceSnapshot: { workOrderNo: 'WO-101', workOrderStatus: '미착수' },
    },
  ],
}

export const reassignmentHandlers = [
  // 094 재배정 계획 상세
  http.get('/api/v1/reassignment-plans/:id', async ({ params }) => {
    await delay(150)
    if (Number(params.id) !== samplePlan.id) {
      return HttpResponse.json(
        {
          error: {
            code: 'RESOURCE_NOT_FOUND',
            message: '요청한 항목을 찾을 수 없습니다.',
            details: { resource: 'reassignment_plan' },
            requestId: 'mock-request-id',
          },
        },
        { status: 404 },
      )
    }
    return HttpResponse.json({ data: samplePlan })
  }),
]
