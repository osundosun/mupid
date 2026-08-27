import { mockQueryResponse } from '../mocks/queryResult'
import type { ApiResponse, QueryRequest, QueryResult } from '../types/query'

const REQUEST_DELAY_MS = 900

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
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

/** 실제 API 호출을 연습하기 위한 Promise 기반 Mock 요청. */
export async function requestQuery(request: QueryRequest): Promise<ApiResponse<QueryResult>> {
  const startedAt = performance.now()

  await delay(REQUEST_DELAY_MS)

  const elapsedTime = (performance.now() - startedAt) / 1000

  return buildResponse(request, Number(elapsedTime.toFixed(2)))
}

/** 첫 화면에서 결과 레이아웃을 보여주기 위한 동기 Mock 응답. */
export function getMockQueryResponse(): ApiResponse<QueryResult> {
  return mockQueryResponse
}
