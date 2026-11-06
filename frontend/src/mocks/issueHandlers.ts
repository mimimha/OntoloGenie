import { http, HttpResponse } from 'msw'
import type { Issue } from '../features/issues/api'

export const issueFixture: Issue = {
  id: 1,
  issueKind: 'UNMATCHED',
  status: 'NEW',
  dedupeKey: 'UNMATCHED:rule:W2E:object:501',
  failureReason: '대응 직원 없음',
  rawValue: '23',
  transformedValue: 'W23',
  evidence: { source: 'MES', causeConfirmed: false },
  objectInstanceId: 501,
  linkMappingId: 12,
  proposalItemId: null,
  forwardedAt: null,
  forwardedBy: null,
  rowVersion: 0,
}

export const issueHandlers = [
  http.get('/api/v1/issues', ({ request }) => {
    const params = new URL(request.url).searchParams
    const page = Number(params.get('page') ?? 0)
    const size = Number(params.get('size') ?? 20)
    const status = params.get('status')
    const kind = params.get('issueKind')
    const matches =
      (!status || status === issueFixture.status) &&
      (!kind || kind === issueFixture.issueKind)
    return HttpResponse.json({
      data: matches && page === 0 ? [issueFixture] : [],
      meta: { page, size, totalElements: matches ? 1 : 0, hasNext: false },
    })
  }),
  http.get('/api/v1/issues/:id', ({ params }) =>
    String(issueFixture.id) === params.id
      ? HttpResponse.json({ data: issueFixture })
      : HttpResponse.json(
          {
            error: {
              code: 'NOT_FOUND',
              message: '문제를 찾을 수 없습니다.',
              details: {},
            },
          },
          { status: 404 },
        ),
  ),
]
