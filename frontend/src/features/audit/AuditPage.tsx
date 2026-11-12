import { useQuery } from '@tanstack/react-query'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { fetchObject } from '../explorer/api'
import ExecutionHistory from './ExecutionHistory'

type ObjectHistoryProps = {
  objectId: number
}

function ObjectHistory({ objectId }: ObjectHistoryProps) {
  const { data: object } = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
  })

  return (
    <ExecutionHistory
      objectId={objectId}
      targetLabel={object?.displayName ?? `객체 #${objectId}`}
    />
  )
}

function AuditPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const objectParam = searchParams.get('objectId')
  const objectId = Number(objectParam)
  const hasObject =
    objectParam !== null &&
    /^\d+$/.test(objectParam) &&
    Number.isSafeInteger(objectId) &&
    objectId > 0

  function goBack() {
    if (location.key !== 'default') {
      navigate(-1)
    } else {
      navigate('/explorer')
    }
  }

  return (
    <div>
      <h1 className="sr-only">실행 이력</h1>

      <section className="rounded-xl border border-slate-200 bg-white p-6">
        <button
          type="button"
          onClick={goBack}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
        >
          이전 화면으로
        </button>

        <div className="mt-5">
          {objectParam !== null && !hasObject ? (
            <p role="alert" className="text-sm text-red-600">
              올바른 객체 번호로 실행 이력을 조회해 주세요.
            </p>
          ) : hasObject ? (
            <ObjectHistory key={objectId} objectId={objectId} />
          ) : (
            <ExecutionHistory />
          )}
        </div>
      </section>
    </div>
  )
}

export default AuditPage
