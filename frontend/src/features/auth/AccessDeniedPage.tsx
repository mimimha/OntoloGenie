import { Link } from 'react-router'
import PageHeader from '../../components/PageHeader'
import PermissionGate from './PermissionGate'

function AccessDeniedPage() {
  return (
    <section role="alert" className="management-page management-panel">
      <PageHeader
        title="접근 권한이 없습니다."
        description="현재 계정으로 이 화면을 사용할 수 없습니다. 권한이 필요하면 관리자에게 문의해 주세요."
      />
      <PermissionGate access="workspace">
        <Link to="/explorer" className="ui-button ui-button--secondary">
          업무 데이터로 돌아가기
        </Link>
      </PermissionGate>
    </section>
  )
}

export default AccessDeniedPage
