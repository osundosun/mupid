import type { QueryRequest } from '../types/query'

export interface QueryFormErrors {
  dbId?: string
  question?: string
}

export function normalizeQueryRequest(request: QueryRequest): QueryRequest {
  return {
    dbId: request.dbId,
    question: request.question.trim(),
  }
}

export function validateQueryRequest(request: QueryRequest): QueryFormErrors {
  const errors: QueryFormErrors = {}

  if (!request.dbId) {
    errors.dbId = '데이터베이스를 선택해 주세요.'
  }

  if (!request.question.trim()) {
    errors.question = '자연어 질문을 입력해 주세요.'
  }

  return errors
}
