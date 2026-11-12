import { useSearchParams } from 'react-router'
import DefinitionChanges from './DefinitionChanges'
import RuleRevisions from './RuleRevisions'

const tabs = [
  { key: 'definitions', label: '정의 변경' },
  { key: 'rules', label: '규칙 개정' },
] as const

type TabKey = (typeof tabs)[number]['key']

// 정의·규칙 이력 페이지. 주소의 ?tab=rules&code=규칙코드로 규칙 개정 탭을 바로 열 수 있다.
// (연결 규칙 화면의 [이력] 버튼이 이 주소로 들어온다)
function HistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const tab: TabKey =
    searchParams.get('tab') === 'rules' ? 'rules' : 'definitions'
  const code = searchParams.get('code') ?? ''

  function selectTab(next: TabKey) {
    setSearchParams(
      next === 'rules' && code ? { tab: next, code } : { tab: next },
    )
  }

  function searchRule(nextCode: string) {
    setSearchParams(
      nextCode ? { tab: 'rules', code: nextCode } : { tab: 'rules' },
    )
  }

  return (
    <div>
      <h1 className="sr-only">정의·규칙 이력</h1>

      <section className="rounded-xl border border-slate-200 bg-white p-6">
        <div role="group" aria-label="이력 종류" className="flex gap-2">
          {tabs.map((item) => (
            <button
              key={item.key}
              type="button"
              aria-pressed={tab === item.key}
              onClick={() => selectTab(item.key)}
              className={`rounded-lg border px-3 py-1.5 text-sm ${
                tab === item.key
                  ? 'border-slate-900 bg-slate-900 text-white'
                  : 'border-slate-300 text-slate-700 hover:border-slate-900'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        <div className="mt-5">
          {tab === 'definitions' ? (
            <DefinitionChanges />
          ) : (
            <RuleRevisions key={code} code={code} onSearch={searchRule} />
          )}
        </div>
      </section>
    </div>
  )
}

export default HistoryPage
