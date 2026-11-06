import { apiGet } from '../../api/client'

export type Issue = {
  id: number
  issueKind: string
  status: string
  dedupeKey: string
  failureReason: string | null
  rawValue: string | null
  transformedValue: string | null
  evidence: Record<string, unknown>
  objectInstanceId: number | null
  linkMappingId: number | null
  proposalItemId: number | null
}

const ISSUE_PAGE_SIZE = 20

export function fetchIssues(
  filters: { status: string; issueKind: string },
  page: number,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({
    page: String(page),
    size: String(ISSUE_PAGE_SIZE),
  })
  if (filters.status) query.set('status', filters.status)
  if (filters.issueKind) query.set('issueKind', filters.issueKind)
  return apiGet<Issue[]>(`/issues?${query}`, { signal })
}

export function fetchIssue(id: number, signal?: AbortSignal) {
  return apiGet<Issue>(`/issues/${id}`, { signal })
}
