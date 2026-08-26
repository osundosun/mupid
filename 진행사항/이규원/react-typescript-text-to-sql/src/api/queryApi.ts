import { mockQueryResponse } from '../mocks/queryResult'
import type { ApiResponse, QueryRequest, QueryResult } from '../types/query'

export function getMockQueryResponse(request?: QueryRequest): ApiResponse<QueryResult> {
  if (!request) {
    return mockQueryResponse
  }

  return {
    ...mockQueryResponse,
    data: {
      ...mockQueryResponse.data,
      dbId: request.dbId,
      question: request.question,
    },
    requestedAt: new Date().toISOString(),
  }
}
