export type RequestStatus = 'idle' | 'loading' | 'success' | 'error'

export interface QueryRequest {
  question: string
}

export type QueryRow = Record<string, string | number | null>

export interface QueryResult {
  question: string
  sql: string
  columns: string[]
  rows: QueryRow[]
  elapsedTime: number
  modelName: string
}

export interface ApiResponse<T> {
  data: T
  requestedAt: string
}
