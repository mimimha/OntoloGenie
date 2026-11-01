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
      maintenance_note: null,
      purchase_price: null,
    },
    restrictedPropertyCodes: ['purchase_price'],
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
      maintenance_note: null,
      purchase_price: null,
    },
    restrictedPropertyCodes: ['purchase_price'],
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
    restrictedPropertyCodes: [],
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
    restrictedPropertyCodes: [],
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
    restrictedPropertyCodes: [],
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
    restrictedPropertyCodes: [],
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

// 객체 유형 ID → 속성 코드별 표시 이름 (077 응답의 propertyNames를 만들 때 사용)
const propertyNamesByType: Record<number, Record<string, string>> = {
  3: {
    equipment_code: '설비 코드',
    process: '공정',
    status: '상태',
    maintenance_note: '정비 메모',
    purchase_price: '구매 가격',
  },
  4: {
    work_order_no: '작업지시 번호',
    process: '공정',
    assigned_equipment: '배정 설비',
    sales_order_no: '수주 번호',
    status: '상태',
  },
  5: { sales_order_no: '수주 번호', status: '상태' },
  6: { note_id: '메모 번호', content: '내용' },
  7: {
    log_id: '로그 번호',
    equipment_code: '설비 코드',
    logged_at: '기록 시각',
    content: '내용',
  },
}

export const handlers = [
  // 076 권한 범위 객체 검색
  http.get('/api/v1/objects', async ({ request }) => {
    await delay()
    const q = new URL(request.url).searchParams.get('q') ?? ''
    const matched = objects.filter((item) => {
      const numbers = Object.values(item.sourceKey ?? {}).map(String)
      return [item.displayName, ...numbers].some((text) => text.includes(q))
    })
    return HttpResponse.json({
      data: matched,
      meta: {
        page: 0,
        size: 20,
        totalElements: matched.length,
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
        propertyNames: propertyNamesByType[object.objectTypeId] ?? {},
        sourceSystemName: source?.systemName ?? null,
        sourceResourceKey: source?.resourceKey ?? null,
      },
    })
  }),
]
