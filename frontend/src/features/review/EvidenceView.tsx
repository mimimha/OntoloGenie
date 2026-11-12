import { EmptyState } from '../../components/Feedback'
import { INTERNAL_EVIDENCE_KEYS, isRow, type Row } from './bundle'
import { changeLabels } from './labels'

// 아래에서 따로 그리는 칸
const SHOWN_KEYS = [
  'reason',
  'holdRecommended',
  'holdReason',
  'items',
  'llmJudgement',
]

const labels: Record<string, string> = {
  originalText: '원문',
  sourceText: '원문',
  excerpt: '원문 발췌',
  observations: '관찰 표현',
  times: '시각 표현',
  changeType: '재분석 비교',
}

function text(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : null
}

function Value({ value }: { value: unknown }) {
  if (value === null || value === undefined) return <>제공되지 않음</>
  if (typeof value === 'object')
    return (
      <pre className="overflow-x-auto rounded bg-slate-50 p-3 text-xs">
        {JSON.stringify(value, null, 2)}
      </pre>
    )
  return <>{String(value)}</>
}

// 근거의 값 예. 요약 문장(snippet)에 이미 들어 있으면 다시 쓰지 않는다.
function sampleValues(item: Row) {
  const values = Array.isArray(item.values)
    ? item.values.filter((v) => typeof v === 'string' || typeof v === 'number')
    : []
  if (!values.length) return null
  const snippet = typeof item.snippet === 'string' ? item.snippet : ''
  return snippet.includes(String(values[0])) ? null : values.join(', ')
}

// 따로 그릴 수 있는 모양인지. 모양이 다르면 아래 일반 목록에 JSON으로 남긴다(조용히 사라지지 않게).
function shownAs(key: string, value: unknown) {
  if (value === null || value === undefined) return true
  if (key === 'items')
    return Array.isArray(value) && value.every((item) => isRow(item))
  if (key === 'holdRecommended') return typeof value === 'boolean'
  return typeof value === 'string'
}

export default function EvidenceView({ evidence }: { evidence: Row }) {
  const reason = text(evidence.reason)
  const held = evidence.holdRecommended === true
  const holdReason = text(evidence.holdReason)
  const judgement = text(evidence.llmJudgement)
  const items = Array.isArray(evidence.items)
    ? evidence.items.filter(isRow)
    : []
  const rest = Object.entries(evidence).filter(
    ([key, value]) =>
      !INTERNAL_EVIDENCE_KEYS.includes(key) &&
      !(SHOWN_KEYS.includes(key) && shownAs(key, value)) &&
      // 보류 권고가 아니면 보류 이유 칸은 비어 있는 값이므로 따로 보이지 않는다
      !(key === 'holdReason' && !held && value === null),
  )
  if (!reason && !items.length && !rest.length && !judgement && !held)
    return <EmptyState message="정보가 제공되지 않았습니다." />
  return (
    <dl className="divide-y divide-slate-100">
      {reason && (
        <div className="grid gap-2 py-3 sm:grid-cols-[160px_1fr]">
          <dt className="font-medium text-slate-600">제안 이유</dt>
          <dd className="min-w-0 whitespace-pre-wrap break-words">{reason}</dd>
        </div>
      )}
      {evidence.holdRecommended === true && (
        <div className="grid gap-2 py-3 sm:grid-cols-[160px_1fr]">
          <dt className="font-medium text-slate-600">보류 권고</dt>
          <dd className="min-w-0 break-words">
            {holdReason ?? '이유가 제공되지 않았습니다.'}
          </dd>
        </div>
      )}
      {judgement && (
        <div className="grid gap-2 py-3 sm:grid-cols-[160px_1fr]">
          <dt className="font-medium text-slate-600">AI 의견</dt>
          <dd className="min-w-0 break-words">{judgement}</dd>
        </div>
      )}
      {items.length > 0 && (
        <div className="grid gap-2 py-3 sm:grid-cols-[160px_1fr]">
          <dt className="font-medium text-slate-600">원천 근거</dt>
          <dd className="min-w-0">
            <ul aria-label="원천 근거" className="space-y-2">
              {items.map((item, index) => (
                <li
                  key={`${index}:${String(item.locator)}`}
                  className="rounded-lg bg-slate-50 p-3"
                >
                  <p className="break-words font-mono text-xs text-slate-700">
                    {text(item.sourceId) ? `${String(item.sourceId)} · ` : ''}
                    {text(item.locator) ?? '위치 없음'}
                  </p>
                  {text(item.snippet) && (
                    <p className="mt-1 break-words text-sm">
                      {String(item.snippet)}
                    </p>
                  )}
                  {sampleValues(item) && (
                    <p className="mt-1 break-words text-sm">
                      값 예: {sampleValues(item)}
                    </p>
                  )}
                  <p className="mt-1 text-xs text-slate-500">
                    {item.verified === true
                      ? '원천에서 다시 확인함'
                      : '원천에서 다시 확인하지 못함'}
                  </p>
                </li>
              ))}
            </ul>
          </dd>
        </div>
      )}
      {rest.map(([key, value]) => (
        <div key={key} className="grid gap-2 py-3 sm:grid-cols-[160px_1fr]">
          <dt className="break-words font-medium text-slate-600">
            {labels[key] ?? key}
          </dt>
          <dd className="min-w-0 whitespace-pre-wrap break-words">
            <Value
              value={
                key === 'changeType' && typeof value === 'string'
                  ? (changeLabels[value] ?? value)
                  : value
              }
            />
          </dd>
        </div>
      ))}
    </dl>
  )
}
