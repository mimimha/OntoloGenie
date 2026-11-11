import { Link } from 'react-router'
import { isRow, transformText } from './bundle'
import type { Proposal } from './types'

export default function RuleContent({ proposal }: { proposal: Proposal }) {
  const { payload } = proposal
  const properties = Array.isArray(payload.properties)
    ? payload.properties.filter(isRow)
    : []
  const conditions = Array.isArray(payload.conditions)
    ? payload.conditions.filter(isRow)
    : []
  const rows = properties.length
    ? properties.map((property) => {
        const column = isRow(property.sourceColumn) ? property.sourceColumn : {}
        return [
          String(column.column ?? '—'),
          transformText(property.transformSteps) || '그대로 연결',
          String(property.name ?? property.code ?? '—'),
        ]
      })
    : conditions.map((condition) => [
        String(condition.sourceProperty ?? '—'),
        [
          transformText(condition.sourceTransforms),
          transformText(condition.targetTransforms),
        ]
          .filter(Boolean)
          .join(' / ') || '그대로 연결',
        String(condition.targetProperty ?? '—'),
      ])
  return (
    <section className="rule-content">
      <div className="flex items-center justify-between gap-4">
        <h3>연결·변환 규칙</h3>
        {proposal.itemKind === 'LINK_RULE' &&
          ['PENDING', 'HELD'].includes(proposal.reviewStatus) && (
            <Link
              className="rules-pill"
              to={`/admin/rules/${proposal.id}/edit`}
            >
              규칙 수정
            </Link>
          )}
      </div>
      {rows.length > 0 && (
        <div className="overflow-x-auto">
          <table aria-label="연결·변환 규칙">
            <thead>
              <tr>
                {['출발 컬럼', '변환 방식', '대상 컬럼·속성'].map((label) => (
                  <th key={label} scope="col">
                    {label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, index) => (
                <tr key={index}>
                  {row.map((value, column) => (
                    <td key={column}>{value}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
