import { useCallback, useState } from 'react'
import { requestQuery } from '../api/queryApi'
import type { ApiResponse, QueryRequest, QueryResult, RequestStatus } from '../types/query'

export function useQueryRequest() {
  const [status, setStatus] = useState<RequestStatus>('idle')
  const [response, setResponse] = useState<ApiResponse<QueryResult> | null>(null)
  const [error, setError] = useState<string | null>(null)

  const run = useCallback(async (request: QueryRequest) => {
    setStatus('loading')
    setError(null)

    try {
      const result = await requestQuery(request)
      setResponse(result)
      setStatus('success')
    } catch {
      setError('요청 중 오류가 발생했습니다.')
      setStatus('error')
    }
  }, [])

  return {
    status,
    response,
    error,
    isLoading: status === 'loading',
    run,
  }
}
