import PageHeader from './PageHeader'
import { Link } from 'react-router'

function NotFoundPage() {
  return (
    <section className="management-page management-panel">
      <PageHeader
        title="페이지를 찾을 수 없어요"
        description="주소가 맞는지 확인해 주세요."
      />
      <Link to="/explorer" className="ui-button ui-button--secondary">
        업무 데이터로 가기
      </Link>
    </section>
  )
}

export default NotFoundPage
