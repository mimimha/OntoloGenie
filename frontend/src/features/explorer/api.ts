import { apiGet } from '../../api/client'
import type { ObjectInstance, ObjectInstanceDetail, ObjectLink } from './types'

export type ObjectFilters = {
  q?: string
  objectTypeId?: number
}

// 076 권한 범위 객체 검색
export function fetchObjects(filters: ObjectFilters) {
  const params = new URLSearchParams()
  if (filters.q) {
    params.set('q', filters.q)
  }
  if (filters.objectTypeId) {
    params.set('objectTypeId', String(filters.objectTypeId))
  }
  const query = params.toString() ? `?${params}` : ''
  return apiGet<ObjectInstance[]>(`/objects${query}`)
}

// 077 객체 상세 조회
export async function fetchObject(id: number) {
  const body = await apiGet<ObjectInstanceDetail>(`/objects/${id}`)
  return body.data
}

// 078 확정 관계·원천 근거 조회
export function fetchObjectLinks(id: number) {
  return apiGet<ObjectLink[]>(`/objects/${id}/links`)
}
