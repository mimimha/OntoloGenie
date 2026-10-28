import { Link } from 'react-router'

function NotFoundPage() {
  return (
    <section>
      <h1 className="text-xl font-semibold text-slate-900">
        페이지를 찾을 수 없어요
      </h1>
      <p className="mt-2 text-slate-600">주소가 맞는지 확인해 주세요.</p>
      <Link
        to="/explorer"
        className="mt-4 inline-block text-slate-900 underline"
      >
        업무 데이터로 가기
      </Link>
    </section>
  )
}

export default NotFoundPage
