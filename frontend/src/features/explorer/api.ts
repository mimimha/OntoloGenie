import { apiGet } from '../../api/client'
import type { ObjectInstance, ObjectInstanceDetail } from './types'

// 076 권한 범위 객체 검색
export function fetchObjects() {
  return apiGet<ObjectInstance[]>('/objects')
}

// 077 객체 상세 조회
export async function fetchObject(id: number) {
  const body = await apiGet<ObjectInstanceDetail>(`/objects/${id}`)
  return body.data
}
