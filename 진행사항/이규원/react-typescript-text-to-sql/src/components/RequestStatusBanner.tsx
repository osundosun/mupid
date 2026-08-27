import type { RequestStatus } from '../types/query'

interface RequestStatusBannerProps {
  status: RequestStatus
  error: string | null
}

const statusLabels: Record<RequestStatus, string> = {
  idle: '대기 중',
  loading: 'SQL 생성 중',
  success: '응답 완료',
  error: '요청 실패',
}

export function RequestStatusBanner({ status, error }: RequestStatusBannerProps) {
  const messages: Record<RequestStatus, string> = {
    idle: '질문을 제출하면 요청 상태에 따라 결과 화면이 변경됩니다.',
    loading: '질문을 SQL로 변환하고 있습니다.',
    success: '생성 SQL과 결과를 표시했습니다.',
    error: error ?? '요청 중 오류가 발생했습니다.',
  }

  return (
    <div
      className={`request-banner ${status}`}
      role={status === 'error' ? 'alert' : 'status'}
      aria-live="polite"
    >
      <div className="request-banner-copy">
        <span className="request-banner-label">
          {status === 'loading' && <span className="request-spinner" aria-hidden="true" />}
          {statusLabels[status]}
        </span>
        <p>{messages[status]}</p>
      </div>
    </div>
  )
}
