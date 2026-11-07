import { apiGet, apiPost } from '../../api/client'
import { ApiError } from '../../api/errors'
import type { PageMeta } from '../../api/types'
import type { CheckStatus } from './types'

export type SyncTrigger =
  'CDC' | 'MANUAL' | 'SCHEDULED' | 'ON_DEMAND' | 'RECOVERY'
export type SyncResource = {
  syncRunId: number
  dataSourceId: number
  sourceResourceId: number
  syncStrategy: 'CDC' | 'FULL_COMPARE'
  mode: 'FULL' | 'INCREMENTAL'
  status: CheckStatus | 'SKIPPED'
  fromLsn: number | null
  toLsn: number | null
  rowsRead: number
  rowsUpserted: number
  rowsDeleted: number
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
}
export type SyncRun = {
  id: number
  syncRunId: number
  dataSourceId: number
  triggerKind: SyncTrigger
  status: CheckStatus
  requestedBy: number | null
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
  resources: SyncResource[]
}
export type SyncAccepted = {
  syncRunId: number | null
  dataSourceId: number
  triggerKind: 'MANUAL'
  status: CheckStatus | null
  requestedBy: number
  requestedAt: string
  joinedExisting: boolean
  resources: (SyncResource & { joined: boolean })[]
}
export type SyncFilters = {
  page: number
  status?: CheckStatus
  triggerKind?: SyncTrigger
  from?: string
  to?: string
}
export type SyncPage = {
  data: SyncRun[]
  meta?: PageMeta & { cdcLastAppliedAt?: string | null }
}

// 073~075: Notion 계약에 따른 연결 함수. 백엔드 제공 후 화면에 주입한다.
export const sourceSyncApi = {
  async request(sourceId: number, sourceResourceIds?: number[]) {
    const body = await apiPost<SyncAccepted>(
      `/sources/${sourceId}/sync-runs`,
      sourceResourceIds ? { sourceResourceIds } : {},
    )
    if (!body)
      throw new ApiError(
        '수집 접수 결과를 확인할 수 없습니다. 이력을 확인해 주세요.',
        { kind: 'invalid-response' },
      )
    return body.data
  },
  list(sourceId: number, filters: SyncFilters): Promise<SyncPage> {
    const params = new URLSearchParams({
      page: String(filters.page),
      size: '20',
    })
    for (const key of ['status', 'triggerKind', 'from', 'to'] as const) {
      if (filters[key]) params.set(key, filters[key])
    }
    return apiGet<SyncRun[], NonNullable<SyncPage['meta']>>(
      `/sources/${sourceId}/sync-runs?${params}`,
    )
  },
  async detail(id: number) {
    return (await apiGet<SyncRun>(`/sync-runs/${id}`)).data
  },
}
export type SourceSyncApi = typeof sourceSyncApi
