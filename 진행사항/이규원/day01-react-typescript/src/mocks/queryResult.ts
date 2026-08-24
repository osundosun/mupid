import type { ApiResponse, QueryResult } from '../types/query'

export const mockQueryResponse: ApiResponse<QueryResult> = {
  data: {
    question: '부서별 평균 급여가 높은 순서대로 보여줘',
    sql: `SELECT
  department_name,
  ROUND(AVG(salary), 0) AS average_salary,
  COUNT(*) AS employee_count
FROM employees
GROUP BY department_name
ORDER BY average_salary DESC
LIMIT 3;`,
    columns: ['department_name', 'average_salary', 'employee_count'],
    rows: [
      {
        department_name: 'Research',
        average_salary: 92500,
        employee_count: 12,
      },
      {
        department_name: 'Engineering',
        average_salary: 88120,
        employee_count: 28,
      },
      {
        department_name: 'Product',
        average_salary: 84600,
        employee_count: 9,
      },
    ],
    elapsedTime: 1.24,
    modelName: 'SmolLM2 Text-to-SQL',
  },
  requestedAt: '2026-08-24T14:30:00+09:00',
}
