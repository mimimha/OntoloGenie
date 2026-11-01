import { apiGet } from '../../api/client'
import type { DefinitionChange, RuleRevision } from './types'

export const DEFINITION_CHANGE_PAGE_SIZE = 20

export type DefinitionChangeFilters = {
  tableName?: string
  from?: string
  to?: string
}

// 044 정의 변경 이력 조회
export function fetchDefinitionChanges(
  filters: DefinitionChangeFilters,
  page = 0,
) {
  const params = new URLSearchParams()
  if (filters.tableName) {
    params.set('tableName', filters.tableName)
  }
  if (filters.from) {
    params.set('from', filters.from)
  }
  if (filters.to) {
    params.set('to', filters.to)
  }
  params.set('page', String(page))
  params.set('size', String(DEFINITION_CHANGE_PAGE_SIZE))
  return apiGet<DefinitionChange[]>(`/ontology/changes?${params}`)
}

// 046 연결 규칙 개정 이력 조회
export async function fetchRuleRevisions(code: string) {
  const body = await apiGet<RuleRevision[]>(
    `/ontology/rules/${encodeURIComponent(code)}/revisions`,
  )
  return body.data
}
