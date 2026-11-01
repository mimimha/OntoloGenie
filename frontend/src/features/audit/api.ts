import { apiGet } from '../../api/client'
import type { Execution, ExecutionStatus } from './types'

export type ExecutionFilters = {
  from?: string
  to?: string
  status?: ExecutionStatus
}

// 080·104가 같이 쓰는 조회 조건을 주소 뒤의 ?from=…&status=… 글자로 만든다.
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
  return params.toString() ? `?${params}` : ''
}

// 080 객체 관련 실행 이력 조회
export function fetchObjectExecutions(id: number, filters: ExecutionFilters) {
  return apiGet<Execution[]>(`/objects/${id}/executions${toQuery(filters)}`)
}

// 104 내 실행 이력
export function fetchMyExecutions(filters: ExecutionFilters) {
  return apiGet<Execution[]>(`/me/executions${toQuery(filters)}`)
}
