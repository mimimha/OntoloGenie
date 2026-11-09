import { apiGet } from '../../api/client'
import type { ReassignmentPlan } from './types'

// 094 재배정 계획 상세 (옮길 작업 목록 포함)
export async function fetchReassignmentPlan(id: number, signal?: AbortSignal) {
  const body = await apiGet<ReassignmentPlan>(`/reassignment-plans/${id}`, {
    signal,
  })
  return body.data
}
