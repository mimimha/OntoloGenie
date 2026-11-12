import { useQuery } from '@tanstack/react-query'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import PageHeader from '../../components/PageHeader'
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
    <div className="management-page">
      <PageHeader
        title="실행 이력"
        description="실행 결과와 처리 내용, 데이터 변경 전후를 확인합니다."
        action={
          <Button variant="secondary" onClick={goBack}>
            이전 화면으로
          </Button>
        }
      />
      <section className="management-panel">
        <div>
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
