import { useState } from 'react'
import './App.css'
import { getMockQueryResponse } from './api/queryApi'
import { DataFlow } from './components/DataFlow'
import { QueryForm } from './components/QueryForm'
import { QueryResultPreview } from './components/QueryResultPreview'
import { useDocumentTitle } from './hooks/useDocumentTitle'
import type { ApiResponse, QueryRequest, QueryResult, RequestStatus } from './types/query'

const initialResponse = getMockQueryResponse()

function App() {
  const [response, setResponse] = useState<ApiResponse<QueryResult>>(initialResponse)
  const [requestStatus] = useState<RequestStatus>('success')

  useDocumentTitle('Day 02 | React Query Form')

  function handleQuerySubmit(request: QueryRequest) {
    setResponse(getMockQueryResponse(request))
  }

  return (
    <main className="app-shell">
      <header className="site-header">
        <a className="brand" href="#top" aria-label="React TypeScript Study 처음으로">
          <span className="brand-mark" aria-hidden="true">
            T
          </span>
          <span>React + TypeScript Study</span>
        </a>
        <span className="day-badge">DAY 02 · QUERY FORM</span>
      </header>

      <section className="hero" id="top">
        <div className="hero-copy">
          <p className="eyebrow">REACT + TYPESCRIPT STUDY</p>
          <h1>
            질문이 데이터가 되는 과정을
            <span>타입으로 설계합니다.</span>
          </h1>
          <p className="hero-description">
            명세서 Skeleton-2의 db_id, question, query 흐름을 React Controlled Component와
            TypeScript 이벤트 타입으로 구현한 2일차 결과물입니다.
          </p>
          <div className="hero-meta" aria-label="프로젝트 구성 정보">
            <div>
              <strong>03</strong>
              <span>Form components</span>
            </div>
            <div>
              <strong>04</strong>
              <span>Example questions</span>
            </div>
            <div>
              <strong>240</strong>
              <span>Character limit</span>
            </div>
          </div>
        </div>

        <aside className="type-card" aria-label="TypeScript 타입 예시">
          <div className="type-card-header">
            <span>query-contract.ts</span>
            <span className="status-dot">controlled</span>
          </div>
          <pre>
            <code>{`interface QueryRequest {
  dbId: string
  question: string
}

interface QueryResult {
  dbId: string
  sql: string
}`}</code>
          </pre>
        </aside>
      </section>

      <section className="section-block query-section" aria-labelledby="query-form-title">
        <div className="section-heading">
          <p>01 · CONTROLLED COMPONENT</p>
          <h2 id="query-form-title">질문을 타입 안전한 요청으로 변환</h2>
          <span>
            데이터베이스와 질문을 React 상태로 관리하고, 유효성 검사를 통과한 값만
            QueryRequest로 전달합니다.
          </span>
        </div>
        <QueryForm
          initialValues={{
            dbId: initialResponse.data.dbId,
            question: initialResponse.data.question,
          }}
          isSubmitting={requestStatus === 'loading'}
          onSubmit={handleQuerySubmit}
        />
      </section>

      <section className="section-block" aria-labelledby="flow-title">
        <div className="section-heading">
          <p>02 · PRODUCT FLOW</p>
          <h2 id="flow-title">하나의 질문이 결과가 되기까지</h2>
        </div>
        <DataFlow />
      </section>

      <section className="section-block preview-section" aria-labelledby="preview-title">
        <div className="section-heading">
          <p>03 · TYPED MOCK RESPONSE</p>
          <h2 id="preview-title">API 응답 타입을 화면으로 검증</h2>
          <span>
            잘못된 필드나 데이터 타입은 브라우저에서 실행하기 전에 TypeScript가 알려줍니다.
          </span>
        </div>
        <QueryResultPreview response={response} status={requestStatus} />
      </section>

      <footer>
        <span>Day 02 complete</span>
        <span>Vite · React · TypeScript · ESLint</span>
      </footer>
    </main>
  )
}

export default App
