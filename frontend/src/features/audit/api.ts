import { apiGet } from '../../api/client'
import type {
  Execution,
  ExecutionCategory,
  ExecutionPageMeta,
  ExecutionStatus,
} from './types'

export type ExecutionFilters = {
  from?: string
  to?: string
  status?: ExecutionStatus
  // 아래는 096에만 있는 조건이다. targetId는 targetType과 같이 보낸다.
  category?: ExecutionCategory
  targetType?: string
  targetId?: number
}

// 조회 조건을 주소 뒤의 ?from=…&status=… 글자로 만든다.
function toQuery(filters: ExecutionFilters) {
  const params = new URLSearchParams()
  if (filters.from) {
    params.set('from', filters.from)
  }
  if (filters.to) {
    params.set('to', filters.to)
  }
  if (filters.status) {
    params.set('status', filters.status)
  }
  if (filters.category) {
    params.set('category', filters.category)
  }
  if (filters.targetType && filters.targetId) {
    params.set('targetType', filters.targetType)
    params.set('targetId', String(filters.targetId))
  }
  return params.toString() ? `?${params}` : ''
}

// 080 객체 관련 실행 이력 조회
export function fetchObjectExecutions(id: number, filters: ExecutionFilters) {
  return apiGet<Execution[], ExecutionPageMeta>(
    `/objects/${id}/executions${toQuery(filters)}`,
  )
}

// 096 실행·감사 이력. 관리자면 전체, 그 밖의 역할은 본인 실행을 서버가 골라 준다.
export function fetchAuditExecutions(filters: ExecutionFilters) {
  return apiGet<Execution[], ExecutionPageMeta>(
    `/audit/actions${toQuery(filters)}`,
  )
}
