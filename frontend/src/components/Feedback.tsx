import { ApiError } from '../api/errors'
import Button from './Button'

export function LoadingState({
  message = '불러오는 중…',
}: {
  message?: string
}) {
  return (
    <div role="status" className="ui-feedback ui-feedback--loading">
      <span aria-hidden="true" className="ui-spinner" />
      {message}
    </div>
  )
}

export function EmptyState({
  message = '조회 결과가 없습니다.',
}: {
  message?: string
}) {
  return (
    <div role="status" className="ui-feedback ui-feedback--empty">
      {message}
    </div>
  )
}

const errorTitles = {
  unauthenticated: '로그인이 필요합니다.',
  forbidden: '요청이 허용되지 않았습니다.',
  validation: '입력 내용을 확인해 주세요.',
  'not-found': '대상을 찾을 수 없습니다.',
  conflict: '최신 상태를 확인해 주세요.',
  server: '서버 요청을 처리하지 못했습니다.',
  http: '요청을 처리하지 못했습니다.',
  network: '서버에 연결하지 못했습니다.',
  aborted: '요청이 취소됐습니다.',
  'invalid-response': '응답을 확인할 수 없습니다.',
}

// 재시도는 호출 화면에서 조회 함수만 명시적으로 전달한다.
export function ErrorState({
  error,
  onRetry,
  title,
}: {
  error: Error
  onRetry?: () => void
  title?: string
}) {
  const apiError = error instanceof ApiError ? error : undefined
  const retryAllowed =
    !apiError ||
    ['server', 'network', 'http', 'invalid-response'].includes(apiError.kind)
  return (
    <div role="alert" className="ui-feedback ui-feedback--error">
      <p className="font-semibold">
        {title ??
          (apiError
            ? errorTitles[apiError.kind]
            : '요청을 처리하지 못했습니다.')}
      </p>
      <p>{error.message}</p>
      {apiError?.fieldErrors.length ? (
        <ul className="list-inside list-disc">
          {apiError.fieldErrors.map(({ field, reason }, index) => (
            <li key={`${field}-${index}`}>
              {field}: {reason}
            </li>
          ))}
        </ul>
      ) : null}
      {apiError?.requestId && (
        <p className="break-all text-xs">요청 ID: {apiError.requestId}</p>
      )}
      {onRetry && retryAllowed && (
        <Button variant="secondary" onClick={onRetry}>
          다시 불러오기
        </Button>
      )}
    </div>
  )
}
