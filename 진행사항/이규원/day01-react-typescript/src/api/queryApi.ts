import { mockQueryResponse } from '../mocks/queryResult'
import type { ApiResponse, QueryResult } from '../types/query'

export function getMockQueryResponse(): ApiResponse<QueryResult> {
  return mockQueryResponse
}
