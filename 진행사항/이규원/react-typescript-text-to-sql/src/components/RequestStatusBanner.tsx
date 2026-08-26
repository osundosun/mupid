import type { QueryError, RequestStatus } from '../types/query'

interface RequestStatusBannerProps {
  status: RequestStatus
  error: QueryError | null
  attempt: number
  canRetry: boolean
  onRetry: () => void
  onCancel: () => void
}

const statusLabels: Record<RequestStatus, string> = {
  idle: '대기 중',
  loading: 'SQL 생성 중',
  success: '응답 완료',
  error: '요청 실패',
}

export function RequestStatusBanner({
  status,
  error,
  attempt,
  canRetry,
  onRetry,
  onCancel,
}: RequestStatusBannerProps) {
  const isCanceled = status === 'idle' && error?.code === 'canceled'

  function renderMessage() {
    if (status === 'loading') {
      return '모델이 질문을 SQL로 변환하고 있습니다. 완료 전까지 새 요청은 전송되지 않습니다.'
    }

    if (status === 'error' && error) {
      return error.message
    }

    if (isCanceled) {
      return '진행 중이던 요청을 취소했습니다. 같은 질문으로 다시 시도할 수 있습니다.'
    }

    if (status === 'success') {
      return '요청이 정상적으로 완료되어 아래에 생성 SQL과 결과를 표시했습니다.'
    }

    return '질문을 제출하면 요청 상태에 따라 로딩, 성공, 실패 화면이 순서대로 표시됩니다.'
  }

  return (
    <div
      className={`request-banner ${isCanceled ? 'canceled' : status}`}
      role={status === 'error' ? 'alert' : 'status'}
      aria-live="polite"
    >
      <div className="request-banner-copy">
        <span className="request-banner-label">
          {status === 'loading' && <span className="request-spinner" aria-hidden="true" />}
          {isCanceled ? '요청 취소됨' : statusLabels[status]}
          {attempt > 0 && <em>attempt {attempt}</em>}
        </span>
        <p>{renderMessage()}</p>
      </div>

      <div className="request-banner-actions">
        {status === 'loading' && (
          <button type="button" className="ghost-button" onClick={onCancel}>
            요청 취소
          </button>
        )}
        {status !== 'loading' && canRetry && (
          <button type="button" className="ghost-button accent" onClick={onRetry}>
            {status === 'error' ? '재시도' : '같은 질문 다시 실행'}
          </button>
        )}
      </div>
    </div>
  )
}
