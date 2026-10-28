import { delay, http, HttpResponse } from 'msw'
import type { ObjectInstance } from '../features/explorer/types'

const objects: ObjectInstance[] = [
  {
    id: 301,
    objectTypeId: 3,
    objectTypeName: '설비',
    displayName: '프레스 3호기',
    properties: {
      equipment_code: 'PR-03',
      process: '프레스',
      status: '정지',
    },
    status: 'ACTIVE',
    sourceMappingId: 21,
    sourceKey: { equipment_code: 'PR-03' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-27T09:00:00+09:00',
  },
  {
    id: 302,
    objectTypeId: 3,
    objectTypeName: '설비',
    displayName: '프레스 1호기',
    properties: {
      equipment_code: 'PR-01',
      process: '프레스',
      status: '가동',
    },
    status: 'ACTIVE',
    sourceMappingId: 21,
    sourceKey: { equipment_code: 'PR-01' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-27T09:00:00+09:00',
  },
  {
    id: 1101,
    objectTypeId: 4,
    objectTypeName: '작업지시',
    displayName: '프레스 작업',
    properties: {
      work_order_no: 'WO-101',
      process: '프레스',
      assigned_equipment: 'PR-03',
      sales_order_no: 'SO-101',
      status: '미착수',
    },
    status: 'ACTIVE',
    sourceMappingId: 22,
    sourceKey: { work_order_no: 'WO-101' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-27T09:00:00+09:00',
  },
  {
    id: 1201,
    objectTypeId: 5,
    objectTypeName: '수주',
    displayName: '생산 수주',
    properties: {
      sales_order_no: 'SO-101',
      status: '진행 중',
    },
    status: 'ACTIVE',
    sourceMappingId: 31,
    sourceKey: { sales_order_no: 'SO-101' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-27T09:00:00+09:00',
  },
  {
    id: 2001,
    objectTypeId: 6,
    objectTypeName: '메모',
    displayName: '야간 인수인계',
    properties: {
      note_id: 'NOTE-01',
      content: '야간에 3호기 진동이 커졌다.',
    },
    status: 'ACTIVE',
    sourceMappingId: 41,
    sourceKey: { note_id: 'NOTE-01' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: null,
  },
  {
    id: 2101,
    objectTypeId: 7,
    objectTypeName: '정비 로그',
    displayName: '3호기 진동 점검',
    properties: {
      log_id: 'MAINT-01',
      equipment_code: 'PR-03',
      logged_at: '2026-10-20 22:00',
      content: '진동 증가에 따른 설비 정지 및 점검',
    },
    status: 'ACTIVE',
    sourceMappingId: 51,
    sourceKey: { log_id: 'MAINT-01' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-20T22:00:00+09:00',
  },
]

type SourceLocation = {
  systemName: string
  resourceKey: string
}

// 원천 매핑 ID → 원천 시스템·테이블 (077 응답의 원천 위치를 만들 때 사용)
const sourceMappings: Record<number, SourceLocation> = {
  21: { systemName: 'MES', resourceKey: 'mes.equipment' },
  22: { systemName: 'MES', resourceKey: 'mes.work_order' },
  31: { systemName: 'ERP', resourceKey: 'erp.sales_order' },
  41: { systemName: '현장 자료', resourceKey: 'docs.handover_note' },
  51: { systemName: '정비 로그', resourceKey: 'maint.maintenance_log' },
}

export const handlers = [
  // 076 권한 범위 객체 검색
  http.get('/api/v1/objects', async () => {
    await delay()
    return HttpResponse.json({
      data: objects,
      meta: {
        page: 0,
        size: 20,
        totalElements: objects.length,
        hasNext: false,
      },
    })
  }),

  // 077 객체 상세 조회
  http.get('/api/v1/objects/:id', async ({ params }) => {
    await delay()
    const object = objects.find((item) => item.id === Number(params.id))
    if (!object) {
      return HttpResponse.json(
        {
          error: {
            code: 'RESOURCE_NOT_FOUND',
            message: '요청한 항목을 찾을 수 없습니다.',
            details: { resource: 'object' },
            requestId: 'mock-request-id',
          },
        },
        { status: 404 },
      )
    }

    const source = object.sourceMappingId
      ? sourceMappings[object.sourceMappingId]
      : undefined
    return HttpResponse.json({
      data: {
        ...object,
        sourceSystemName: source?.systemName ?? null,
        sourceResourceKey: source?.resourceKey ?? null,
      },
    })
  }),
]
