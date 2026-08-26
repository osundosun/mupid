import {
  REQUEST_DELAY_MS,
  failureCodeForAttempt,
  shouldFailAttempt,
} from '../mocks/queryScenario'
import { mockQueryResponse } from '../mocks/queryResult'
import type { ApiResponse, QueryRequest, QueryResult } from '../types/query'
import { QueryRequestError } from './queryError'

interface RequestQueryOptions {
  signal?: AbortSignal
  /** 실패 시나리오를 계산하기 위한 누적 시도 횟수 */
  attempt?: number
}

/** 요청 취소를 지원하는 지연 함수. AbortSignal이 끊기면 즉시 reject한다. */
function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason ?? new DOMException('Aborted', 'AbortError'))
      return
    }

    const timerId = window.setTimeout(() => {
      signal?.removeEventListener('abort', handleAbort)
      resolve()
    }, ms)

    function handleAbort() {
      window.clearTimeout(timerId)
      reject(signal?.reason ?? new DOMException('Aborted', 'AbortError'))
    }

    signal?.addEventListener('abort', handleAbort, { once: true })
  })
}

function buildResponse(request: QueryRequest, elapsedTime: number): ApiResponse<QueryResult> {
  return {
    ...mockQueryResponse,
    data: {
      ...mockQueryResponse.data,
      dbId: request.dbId,
      question: request.question,
      elapsedTime,
    },
    requestedAt: new Date().toISOString(),
  }
}

/**
 * Mock Text-to-SQL 요청. 실제 fetch로 교체할 수 있도록 Promise와 AbortSignal 계약을
 * 그대로 사용하고, 성공 시 ApiResponse<QueryResult>를 반환한다.
 */
export async function requestQuery(
  request: QueryRequest,
  { signal, attempt = 1 }: RequestQueryOptions = {},
): Promise<ApiResponse<QueryResult>> {
  const startedAt = performance.now()

  await delay(REQUEST_DELAY_MS, signal)

  if (shouldFailAttempt(attempt)) {
    throw new QueryRequestError(failureCodeForAttempt(attempt))
  }

  const elapsedTime = (performance.now() - startedAt) / 1000

  return buildResponse(request, Number(elapsedTime.toFixed(2)))
}

/** 첫 화면에서 결과 레이아웃을 보여주기 위한 동기 Mock 응답. */
export function getMockQueryResponse(): ApiResponse<QueryResult> {
  return mockQueryResponse
}
