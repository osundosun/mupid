import type { DatabaseOption } from '../types/query'

export const databaseOptions: DatabaseOption[] = [
  {
    id: 'department_management',
    label: 'Department Management',
    description: '부서와 부서장 정보를 조회하는 Spider 예제 데이터베이스',
  },
  {
    id: 'concert_singer',
    label: 'Concert Singer',
    description: '가수, 콘서트, 경기장 관계를 포함한 음악 도메인 데이터베이스',
  },
  {
    id: 'employee_hire_evaluation',
    label: 'Employee Evaluation',
    description: '채용 및 직원 평가 정보를 다루는 인사 도메인 데이터베이스',
  },
]
