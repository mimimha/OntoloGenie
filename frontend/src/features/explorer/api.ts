import { apiGet } from '../../api/client'
import type {
  LinkDirection,
  ModelGraph,
  ObjectInstance,
  ObjectInstanceDetail,
  ObjectLink,
  ObjectLinkGroup,
} from './types'

export const OBJECT_PAGE_SIZE = 20

export type ObjectFilters = {
  q?: string
  objectTypeId?: number
}

// 076 권한 범위 객체 검색
export function fetchObjects(filters: ObjectFilters, page = 0) {
  const params = new URLSearchParams()
  if (filters.q) {
    params.set('q', filters.q)
  }
  if (filters.objectTypeId) {
    params.set('objectTypeId', String(filters.objectTypeId))
  }
  params.set('page', String(page))
  params.set('size', String(OBJECT_PAGE_SIZE))
  return apiGet<ObjectInstance[]>(`/objects?${params}`)
}

// 077 객체 상세 조회
export async function fetchObject(id: number) {
  const body = await apiGet<ObjectInstanceDetail>(`/objects/${id}`)
  return body.data
}

// 078 API의 size 최대값
export const LINK_PAGE_SIZE = 100

// 관계 유형·방향 하나로 좁힌 078 조회 조건
export type LinkGroupFilter = {
  linkTypeId: number
  direction: LinkDirection
}

// 078 확정 관계·원천 근거 조회
export function fetchObjectLinks(
  id: number,
  page = 0,
  filter?: LinkGroupFilter,
) {
  const params = new URLSearchParams()
  if (filter) {
    params.set('linkTypeId', String(filter.linkTypeId))
    params.set('direction', filter.direction)
  }
  params.set('page', String(page))
  params.set('size', String(LINK_PAGE_SIZE))
  return apiGet<ObjectLink[]>(`/objects/${id}/links?${params}`)
}

// 관계 유형·방향별 연결 건수. 관계도가 첫 화면에 모든 묶음을 그릴 때 쓴다.
export async function fetchObjectLinkGroups(id: number) {
  const body = await apiGet<ObjectLinkGroup[]>(`/objects/${id}/link-groups`)
  return body.data
}

// 042 객체 유형 관계도
export async function fetchModelGraph() {
  const body = await apiGet<ModelGraph>('/ontology/model-graph')
  return body.data
}
