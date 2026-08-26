import type { QueryError, QueryErrorCode } from '../types/query'

const errorMessages: Record<QueryErrorCode, string> = {
  network: '서버에 연결하지 못했습니다. 네트워크 상태를 확인한 뒤 다시 시도해 주세요.',
  timeout: '응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.',
  server: 'SQL을 생성하는 중 서버 오류가 발생했습니다. 다시 시도해 주세요.',
  canceled: '요청을 취소했습니다.',
}

export class QueryRequestError extends Error {
  readonly code: QueryErrorCode

  constructor(code: QueryErrorCode) {
    super(errorMessages[code])
    this.name = 'QueryRequestError'
    this.code = code
  }
}

/** 어떤 형태의 예외가 오더라도 화면에서 사용할 QueryError 한 가지로 정규화한다. */
export function toQueryError(error: unknown): QueryError {
  if (error instanceof QueryRequestError) {
    return {
      code: error.code,
      message: error.message,
      retryable: error.code !== 'canceled',
    }
  }

  if (error instanceof DOMException && error.name === 'AbortError') {
    return {
      code: 'canceled',
      message: errorMessages.canceled,
      retryable: false,
    }
  }

  return {
    code: 'server',
    message: errorMessages.server,
    retryable: true,
  }
}
