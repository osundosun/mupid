import './App.css'
import { getMockQueryResponse } from './api/queryApi'
import { DataFlow } from './components/DataFlow'
import { QueryResultPreview } from './components/QueryResultPreview'
import { useDocumentTitle } from './hooks/useDocumentTitle'
import type { RequestStatus } from './types/query'

const response = getMockQueryResponse()
const requestStatus: RequestStatus = 'success'

function App() {
  useDocumentTitle('Day 01 | React + TypeScript')

  return (
    <main className="app-shell">
      <header className="site-header">
        <a className="brand" href="#top" aria-label="React TypeScript Study 처음으로">
          <span className="brand-mark" aria-hidden="true">
            T
          </span>
          <span>React + TypeScript Study</span>
        </a>
        <span className="day-badge">DAY 01 · FOUNDATION</span>
      </header>

      <section className="hero" id="top">
        <div className="hero-copy">
          <p className="eyebrow">REACT + TYPESCRIPT STUDY</p>
          <h1>
            질문이 데이터가 되는 과정을
            <span>타입으로 설계합니다.</span>
          </h1>
          <p className="hero-description">
            자연어 질문부터 SQL 결과 테이블까지, API 흐름에 필요한 타입과 프로젝트 구조를
            먼저 정의한 1일차 결과물입니다.
          </p>
          <div className="hero-meta" aria-label="프로젝트 구성 정보">
            <div>
              <strong>07</strong>
              <span>Source modules</span>
            </div>
            <div>
              <strong>100%</strong>
              <span>Typed mock data</span>
            </div>
            <div>
              <strong>0</strong>
              <span>Implicit any</span>
            </div>
          </div>
        </div>

        <aside className="type-card" aria-label="TypeScript 타입 예시">
          <div className="type-card-header">
            <span>query.ts</span>
            <span className="status-dot">typed</span>
          </div>
          <pre>
            <code>{`interface QueryResult {
  question: string
  sql: string
  columns: string[]
  rows: Array<Record<
    string,
    string | number | null
  >>
  elapsedTime: number
  modelName: string
}`}</code>
          </pre>
        </aside>
      </section>

      <section className="section-block" aria-labelledby="flow-title">
        <div className="section-heading">
          <p>01 · PRODUCT FLOW</p>
          <h2 id="flow-title">하나의 질문이 결과가 되기까지</h2>
        </div>
        <DataFlow />
      </section>

      <section className="section-block preview-section" aria-labelledby="preview-title">
        <div className="section-heading">
          <p>02 · TYPED MOCK RESPONSE</p>
          <h2 id="preview-title">API 응답 타입을 화면으로 검증</h2>
          <span>
            잘못된 필드나 데이터 타입은 브라우저에서 실행하기 전에 TypeScript가 알려줍니다.
          </span>
        </div>
        <QueryResultPreview response={response} status={requestStatus} />
      </section>

      <footer>
        <span>Day 01 complete</span>
        <span>Vite · React · TypeScript · ESLint</span>
      </footer>
    </main>
  )
}

export default App
