import type { ApiResponse, QueryResult } from '../types/query'

export const mockQueryResponse: ApiResponse<QueryResult> = {
  data: {
    dbId: 'department_management',
    question: 'How many heads of the departments are older than 56?',
    sql: `SELECT COUNT(*) AS head_count
FROM head
WHERE age > 56;`,
    columns: ['head_count'],
    rows: [
      {
        head_count: 2,
      },
    ],
    elapsedTime: 1.24,
    modelName: 'SmolLM2 Text-to-SQL',
  },
  requestedAt: '2026-08-24T14:30:00+09:00',
}
