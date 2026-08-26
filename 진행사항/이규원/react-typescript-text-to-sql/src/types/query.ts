export type RequestStatus = 'idle' | 'loading' | 'success' | 'error'

export type QueryErrorCode = 'network' | 'timeout' | 'server' | 'canceled'

export interface QueryRequest {
  dbId: string
  question: string
}

export interface DatabaseOption {
  id: string
  label: string
  description: string
}

export interface ExampleQuestion {
  id: string
  dbId: string
  label: string
  question: string
}

export type QueryRow = Record<string, string | number | null>

export interface QueryResult {
  dbId: string
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

export interface QueryError {
  code: QueryErrorCode
  message: string
  retryable: boolean
}
