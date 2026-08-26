import { useCallback, useEffect, useRef, useState } from 'react'
import { requestQuery } from '../api/queryApi'
import { toQueryError } from '../api/queryError'
import type {
  ApiResponse,
  QueryError,
  QueryRequest,
  QueryResult,
  RequestStatus,
} from '../types/query'

export interface UseQueryRequestResult {
  status: RequestStatus
  response: ApiResponse<QueryResult> | null
  error: QueryError | null
  /** 서버에 보낸 누적 요청 횟수 */
  attempt: number
  isLoading: boolean
  canRetry: boolean
  run: (request: QueryRequest) => void
  retry: () => void
  cancel: () => void
}

/**
 * Text-to-SQL 요청의 로딩·성공·실패 상태와 취소·재시도를 함께 관리한다.
 * 요청 중에는 새 요청을 막고, 마지막 요청을 기억해 재시도에 사용한다.
 */
export function useQueryRequest(): UseQueryRequestResult {
  const [status, setStatus] = useState<RequestStatus>('idle')
  const [response, setResponse] = useState<ApiResponse<QueryResult> | null>(null)
  const [error, setError] = useState<QueryError | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [hasRequest, setHasRequest] = useState(false)

  const controllerRef = useRef<AbortController | null>(null)
  const lastRequestRef = useRef<QueryRequest | null>(null)
  const attemptRef = useRef(0)
  const requestIdRef = useRef(0)
  const isMountedRef = useRef(true)

  useEffect(() => {
    isMountedRef.current = true

    return () => {
      isMountedRef.current = false
      controllerRef.current?.abort()
    }
  }, [])

  const execute = useCallback(async (request: QueryRequest) => {
    // 중복 요청 방지: 진행 중인 요청이 있으면 새 요청을 시작하지 않는다.
    if (controllerRef.current) {
      return
    }

    const controller = new AbortController()
    const requestId = requestIdRef.current + 1

    controllerRef.current = controller
    requestIdRef.current = requestId
    lastRequestRef.current = request
    attemptRef.current += 1

    setStatus('loading')
    setError(null)
    setAttempt(attemptRef.current)
    setHasRequest(true)

    try {
      const result = await requestQuery(request, {
        signal: controller.signal,
        attempt: attemptRef.current,
      })

      // 취소되었거나 더 최신 요청이 시작된 응답은 화면에 반영하지 않는다.
      if (!isMountedRef.current || requestIdRef.current !== requestId) {
        return
      }

      setResponse(result)
      setStatus('success')
    } catch (caught) {
      if (!isMountedRef.current || requestIdRef.current !== requestId) {
        return
      }

      const queryError = toQueryError(caught)

      setError(queryError)
      setStatus(queryError.code === 'canceled' ? 'idle' : 'error')
    } finally {
      if (controllerRef.current === controller) {
        controllerRef.current = null
      }
    }
  }, [])

  const run = useCallback(
    (request: QueryRequest) => {
      void execute(request)
    },
    [execute],
  )

  const retry = useCallback(() => {
    const lastRequest = lastRequestRef.current

    if (!lastRequest) {
      return
    }

    void execute(lastRequest)
  }, [execute])

  const cancel = useCallback(() => {
    controllerRef.current?.abort()
  }, [])

  return {
    status,
    response,
    error,
    attempt,
    isLoading: status === 'loading',
    canRetry: hasRequest && status !== 'loading',
    run,
    retry,
    cancel,
  }
}
