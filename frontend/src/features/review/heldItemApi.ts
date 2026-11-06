import { apiGet, ApiError } from '../../api/client'

export type HeldItem = {
  id: number
  proposalItemId: number | null
  linkMappingId: number | null
  sourceObjectId: number
  linkTypeId: number
  status: string
  rawValue: string | null
  transformedValue: string | null
  holdReason: string | null
  resolvedLinkId: number | null
  resolvedSyncRunId: number | null
  resolvedBy: number | null
  resolvedAt: string | null
  resolutionNote: string | null
  createdAt: string
}

export const HELD_ITEM_PAGE_SIZE = 20

export async function fetchHeldItems(page: number, signal?: AbortSignal) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(HELD_ITEM_PAGE_SIZE),
  })
  const response = await apiGet<HeldItem[]>(`/held-items?${params}`, { signal })
  const meta = response.meta
  if (
    !Array.isArray(response.data) ||
    meta?.page !== page ||
    meta.size !== HELD_ITEM_PAGE_SIZE ||
    !Number.isSafeInteger(meta.totalElements) ||
    meta.totalElements < 0
  )
    throw new ApiError('보류 건 목록의 페이지 정보를 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  return { data: response.data, meta }
}

export async function fetchHeldItem(id: number, signal?: AbortSignal) {
  const { data } = await apiGet<HeldItem>(`/held-items/${id}`, { signal })
  if (data?.id !== id)
    throw new ApiError('요청한 보류 건과 응답이 일치하지 않습니다.', {
      kind: 'invalid-response',
    })
  return data
}
