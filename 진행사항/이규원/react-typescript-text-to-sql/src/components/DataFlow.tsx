interface FlowStep {
  title: string
  description: string
}

const flowSteps: FlowStep[] = [
  { title: '질문 입력', description: '사용자가 자연어로 데이터 질문을 입력합니다.' },
  { title: 'API 요청', description: 'QueryRequest 타입에 맞춰 질문을 전달합니다.' },
  { title: 'SQL 응답', description: '모델이 질문을 실행 가능한 SQL로 변환합니다.' },
  { title: '결과 출력', description: 'SQL 코드와 동적 데이터 테이블을 표시합니다.' },
]

export function DataFlow() {
  return (
    <div className="flow-grid">
      {flowSteps.map((step, index) => (
        <article className="flow-item" key={step.title}>
          <span className="flow-step">0{index + 1}</span>
          <h3>{step.title}</h3>
          <p>{step.description}</p>
        </article>
      ))}
    </div>
  )
}
