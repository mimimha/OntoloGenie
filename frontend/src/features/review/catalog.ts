import type { ApiResponse } from '../../api/types'
import type { Proposal } from './types'

// Counts and search include candidates beyond the first server page.
export async function collectProposals(
  fetchPage: (page: number) => Promise<ApiResponse<Proposal[]>>,
) {
  let response = await fetchPage(0)
  const result = [...response.data]
  let page = 0
  while (response.meta?.hasNext) {
    response = await fetchPage(++page)
    result.push(...response.data)
  }
  return result
}

export function isObjectProposal(item: Proposal) {
  return [
    'OBJECT_TYPE',
    'PROPERTY',
    'OBJECT_MAPPING',
    'PROPERTY_MAPPING',
  ].includes(item.itemKind)
}

export function matchesProposal(item: Proposal, query: string) {
  return JSON.stringify([item.payload, item.evidence, item.id])
    .toLocaleLowerCase()
    .includes(query.trim().toLocaleLowerCase())
}
