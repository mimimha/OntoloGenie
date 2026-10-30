import { apiGet } from '../../api/client'
import type { ObjectInstance, ObjectInstanceDetail } from './types'

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
