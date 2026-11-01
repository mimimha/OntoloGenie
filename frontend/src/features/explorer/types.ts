export type ObjectStatus = 'ACTIVE' | 'DELETED_AT_SOURCE'

// 076 권한 범위 객체 검색 응답의 객체 하나
export type ObjectInstance = {
  id: number
  objectTypeId: number
  objectTypeName: string
  displayName: string
  properties: Record<string, unknown>
  restrictedPropertyCodes: string[]
  status: ObjectStatus
  sourceMappingId?: number | null
  sourceKey?: Record<string, unknown> | null
  firstCollectedAt: string
  lastSyncedAt?: string | null
  dataAsOf?: string | null
}

// 077 객체 상세 조회 응답: 목록 필드 + 속성 표시 이름 + 원천 위치
export type ObjectInstanceDetail = ObjectInstance & {
  propertyNames: Record<string, string>
  sourceSystemName?: string | null
  sourceResourceKey?: string | null
}
