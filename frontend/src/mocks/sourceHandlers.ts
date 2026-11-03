import { delay, http, HttpResponse } from 'msw'
import type {
  ConnectionCheck,
  Source,
  SourceDetail,
  SourceResource,
} from '../features/sources/types'
import { sourceColumns, sourceResources } from './sourceData'

// 원천 코드는 infra/cdc/README.md, 접속 주소 형식은 API 명세 010 예시를 따른다.
const initialSources: Source[] = [
  {
    id: 1,
    code: 'ERP_MAIN',
    name: 'ERP 운영 DB',
    sourceKind: 'POSTGRES',
    connectionStatus: 'CONNECTED',
    lastCheckedAt: '2026-11-03T09:00:00+09:00',
    syncEnabled: true,
    syncIntervalMinutes: null,
    rowVersion: 1,
    connectionConfig: { host: 'erp-db.internal', port: 5432, database: 'erp' },
  },
  {
    id: 2,
    code: 'MES_MAIN',
    name: 'MES 운영 DB',
    sourceKind: 'POSTGRES',
    connectionStatus: 'UNVERIFIED',
    lastCheckedAt: null,
    syncEnabled: true,
    syncIntervalMinutes: null,
    rowVersion: 1,
    connectionConfig: { host: 'mes-db.internal', port: 5432, database: 'mes' },
  },
]

// 계정 이름은 infra/source-db/sql/setup_roles.sql의 조회 전용 계정과 같다.
const defaultCredentials: SourceDetail['credentials'] = [
  { purpose: 'SYNC', username: 'platform_ro' },
  { purpose: 'AI', username: 'platform_ai' },
]

// 등록·수정으로 바뀌는 가짜 상태. 테스트마다 resetSourceMocks로 되돌린다.
let sources: Source[] = []
let credentials: Record<number, SourceDetail['credentials']> = {}

export function resetSourceMocks() {
  sources = initialSources.map((source) => ({
    ...source,
    connectionConfig: { ...source.connectionConfig },
  }))
  credentials = { 1: defaultCredentials, 2: defaultCredentials }
}
resetSourceMocks()

const latestChecks: Record<number, SourceDetail['latestConnectionCheck']> = {
  1: {
    checkId: 100,
    status: 'SUCCEEDED',
    finishedAt: '2026-11-03T09:00:03+09:00',
    errorMessage: null,
  },
  2: null,
}

let nextCheckId = 100

function readableCounts(sourceId: number) {
  const resources = sourceResources.filter(
    (resource) => resource.dataSourceId === sourceId && resource.isReadable,
  )
  const columns = resources.reduce(
    (sum, resource) => sum + (sourceColumns[resource.id]?.length ?? 0),
    0,
  )
  return { tables: resources.length, columns }
}

function notFound(message: string) {
  return HttpResponse.json(
    {
      error: {
        code: 'RESOURCE_NOT_FOUND',
        message,
        details: {},
        requestId: 'mock-request-id',
      },
    },
    { status: 404 },
  )
}

export const sourceHandlers = [
  // 009 원천 목록 조회
  http.get('/api/v1/sources', async () => {
    await delay(150)
    return HttpResponse.json({
      data: sources,
      meta: {
        page: 0,
        size: 100,
        totalElements: sources.length,
        hasNext: false,
      },
    })
  }),

  // 011 원천 상세 조회
  http.get('/api/v1/sources/:id', async ({ params }) => {
    await delay(150)
    const source = sources.find((item) => item.id === Number(params.id))
    if (!source) {
      return notFound('원천을 찾을 수 없습니다.')
    }
    const detail: SourceDetail = {
      ...source,
      credentials: credentials[source.id] ?? [],
      latestConnectionCheck: latestChecks[source.id] ?? null,
    }
    return HttpResponse.json({ data: detail })
  }),

  // 013 DB 접속 및 읽기 범위 검사 요청
  http.post('/api/v1/sources/:id/test-connection', async ({ params }) => {
    await delay(150)
    if (!sources.some((source) => source.id === Number(params.id))) {
      return notFound('원천을 찾을 수 없습니다.')
    }
    nextCheckId += 1
    return HttpResponse.json(
      {
        data: {
          checkId: nextCheckId,
          status: 'QUEUED',
          joined: false,
          requestedAt: new Date().toISOString(),
          startedAt: null,
          finishedAt: null,
          errorMessage: null,
        },
      },
      { status: 202 },
    )
  }),

  // 100 원천 연결 검사 결과 조회 (가짜 데이터는 바로 끝난 결과를 준다)
  http.get(
    '/api/v1/sources/:id/connection-checks/:checkId',
    async ({ params }) => {
      await delay(150)
      const sourceId = Number(params.id)
      const counts = readableCounts(sourceId)
      const now = new Date().toISOString()
      const check: ConnectionCheck = {
        checkId: Number(params.checkId),
        dataSourceId: sourceId,
        status: 'SUCCEEDED',
        requestedBy: 1,
        requestedAt: now,
        startedAt: now,
        finishedAt: now,
        errorMessage: null,
        result: {
          accounts: (['SYNC', 'AI'] as const).map((purpose) => ({
            purpose,
            status: 'SUCCEEDED' as const,
            readableTableCount: counts.tables,
            readableColumnCount: counts.columns,
            errorMessage: null,
          })),
        },
      }
      return HttpResponse.json({ data: check })
    },
  ),

  // 014 원천 표·뷰·자료 목록
  http.get('/api/v1/sources/:id/resources', async ({ params, request }) => {
    await delay(150)
    const url = new URL(request.url)
    const q = url.searchParams.get('q')?.toLowerCase()
    const kind = url.searchParams.get('resourceKind')
    const readable = url.searchParams.get('isReadable')
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    const matched: SourceResource[] = sourceResources.filter(
      (resource) =>
        resource.dataSourceId === Number(params.id) &&
        (!q || resource.resourceKey.toLowerCase().includes(q)) &&
        (!kind || resource.resourceKind === kind) &&
        (readable === null || String(resource.isReadable) === readable),
    )
    return HttpResponse.json({
      data: matched.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalElements: matched.length,
        hasNext: (page + 1) * size < matched.length,
      },
    })
  }),

  // 015 자원의 컬럼 조회
  http.get(
    '/api/v1/sources/:id/resources/:resourceId/columns',
    async ({ params }) => {
      await delay(150)
      const columns = sourceColumns[Number(params.resourceId)]
      if (!columns) {
        return notFound('원천 자원을 찾을 수 없습니다.')
      }
      return HttpResponse.json({ data: columns })
    },
  ),
]
