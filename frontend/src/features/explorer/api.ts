import { apiGet } from '../../api/client'
import type { ObjectInstance, ObjectInstanceDetail, ObjectLink } from './types'

// 076 권한 범위 객체 검색
export function fetchObjects(q: string) {
  const query = q ? `?${new URLSearchParams({ q })}` : ''
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
