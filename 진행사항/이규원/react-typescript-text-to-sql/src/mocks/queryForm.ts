import type { DatabaseOption, ExampleQuestion } from '../types/query'

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

export const exampleQuestions: ExampleQuestion[] = [
  {
    id: 'older-heads',
    dbId: 'department_management',
    label: '56세 초과 부서장 수',
    question: 'How many heads of the departments are older than 56?',
  },
  {
    id: 'department-list',
    dbId: 'department_management',
    label: '부서 이름 정렬',
    question: 'List all department names in alphabetical order.',
  },
  {
    id: 'concert-count',
    dbId: 'concert_singer',
    label: '연도별 콘서트 수',
    question: 'How many concerts were held in each year?',
  },
  {
    id: 'employee-score',
    dbId: 'employee_hire_evaluation',
    label: '평균 평가 점수',
    question: 'Show the average evaluation score for each employee.',
  },
]
