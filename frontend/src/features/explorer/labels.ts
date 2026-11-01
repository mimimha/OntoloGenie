import type { ObjectStatus } from './types'

export const statusLabels: Record<ObjectStatus, string> = {
  ACTIVE: '정상',
  DELETED_AT_SOURCE: '원천에서 삭제됨',
}
