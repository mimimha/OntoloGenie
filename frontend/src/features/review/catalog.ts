import type { ApiResponse } from '../../api/types'
import { INTERNAL_EVIDENCE_KEYS, isRow } from './bundle'
import { proposalTitle } from './labels'
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

// 화면에 보이는 글자(이름·코드·원천 표·컬럼·설명·근거)만 모은다. 내부 대조용 값은 뺀다.
function shownText(value: unknown, out: string[]) {
  if (typeof value === 'string') out.push(value)
  // 근거 표본 값처럼 화면에 숫자로 보이는 값도 찾는다
  else if (typeof value === 'number') out.push(String(value))
  else if (Array.isArray(value)) for (const item of value) shownText(item, out)
  else if (isRow(value))
    for (const [key, item] of Object.entries(value))
      if (!INTERNAL_EVIDENCE_KEYS.includes(key)) shownText(item, out)
  return out
}

export function matchesProposal(item: Proposal, query: string) {
  const keyword = query.trim().toLocaleLowerCase()
  if (!keyword) return true
  // 후보 번호(#12)로도 찾는다
  return shownText(
    [item.payload, item.evidence],
    [proposalTitle(item), `#${item.id}`],
  ).some((text) => text.toLocaleLowerCase().includes(keyword))
}
