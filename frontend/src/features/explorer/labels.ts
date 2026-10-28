import type { ObjectInstance, ObjectStatus } from './types'

export const statusLabels: Record<ObjectStatus, string> = {
  ACTIVE: '정상',
  DELETED_AT_SOURCE: '원천에서 삭제됨',
}

export function objectTypeLabel(object: ObjectInstance) {
  return object.objectTypeName ?? `유형 ${object.objectTypeId}`
}
