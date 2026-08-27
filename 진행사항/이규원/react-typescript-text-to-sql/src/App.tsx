import { useEffect } from 'react'
import './App.css'
import { getMockQueryResponse } from './api/queryApi'
import { DataFlow } from './components/DataFlow'
import { QueryForm } from './components/QueryForm'
import { QueryHistory } from './components/QueryHistory'
import { QueryResultPreview } from './components/QueryResultPreview'
import { RequestStatusBanner } from './components/RequestStatusBanner'
import { useDocumentTitle } from './hooks/useDocumentTitle'
import { useQueryHistory } from './hooks/useQueryHistory'
import { useQueryRequest } from './hooks/useQueryRequest'
import { FAILURE_INTERVAL } from './mocks/queryScenario'

const initialRequest = getMockQueryResponse().data

function App() {
  const { status, response, error, attempt, isLoading, canRetry, run, retry, cancel } =
    useQueryRequest()
  const { entries, add } = useQueryHistory()

  useDocumentTitle('Day 04 | Result History UI')

  useEffect(() => {
    if (status === 'success' && response) {
      add(response)
    }
  }, [add, response, status])

  return (
    <main className="app-shell">
      <header className="site-header">
        <a className="brand" href="#top" aria-label="React TypeScript Study 처음으로">
          <span className="brand-mark" aria-hidden="true">
            T
          </span>
          <span>React + TypeScript Study</span>
        </a>
        <span className="day-badge">DAY 04 · RESULT HISTORY</span>
      </header>

      <section className="hero" id="top">
        <div className="hero-copy">
          <p className="eyebrow">REACT + TYPESCRIPT STUDY</p>
          <h1>
            생성된 결과를
            <span>다시 쓰는 경험까지 설계합니다.</span>
          </h1>
          <p className="hero-description">
            생성 SQL 복사와 최근 성공 질문 재실행을 더해 Text-to-SQL 응답을 다시 활용할 수
            있도록 확장한 4일차 결과물입니다.
          </p>
          <div className="hero-meta" aria-label="요청 처리 구성 정보">
            <div>
              <strong>01</strong>
              <span>Copy action</span>
            </div>
            <div>
              <strong>03</strong>
              <span>Recent queries</span>
            </div>
            <div>
              <strong>01</strong>
              <span>History state</span>
            </div>
          </div>
        </div>

        <aside className="type-card" aria-label="TypeScript 타입 예시">
          <div className="type-card-header">
            <span>query-history.ts</span>
            <span className="status-dot">stateful</span>
          </div>
          <pre>
            <code>{`interface QueryHistoryEntry {
  id: string
  data: QueryResult
}

setEntries((current) =>
  [entry, ...current].slice(0, 3),
)`}</code>
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
            dbId: initialRequest.dbId,
            question: initialRequest.question,
          }}
          isSubmitting={isLoading}
          onSubmit={run}
        />
      </section>

      <section className="section-block" aria-labelledby="flow-title">
        <div className="section-heading">
          <p>02 · PRODUCT FLOW</p>
          <h2 id="flow-title">하나의 질문이 결과가 되기까지</h2>
        </div>
        <DataFlow />
      </section>

      <section className="section-block" aria-labelledby="request-state-title">
        <div className="section-heading">
          <p>03 · REQUEST STATE</p>
          <h2 id="request-state-title">요청의 네 가지 상태를 화면으로 관리</h2>
          <span>
            요청 중에는 중복 전송을 막고 취소 버튼을, 실패하면 오류 원인과 재시도 버튼을
            제공합니다. 학습용 Mock은 {FAILURE_INTERVAL}번째 요청마다 실패해 재시도 흐름을
            확인할 수 있습니다.
          </span>
        </div>
        <RequestStatusBanner
          status={status}
          error={error}
          attempt={attempt}
          canRetry={canRetry}
          onRetry={retry}
          onCancel={cancel}
        />
      </section>

      <section className="section-block preview-section" aria-labelledby="preview-title">
        <div className="section-heading">
          <p>04 · TYPED ASYNC RESPONSE</p>
          <h2 id="preview-title">상태에 따라 달라지는 결과 화면</h2>
          <span>
            같은 결과 영역이 대기, 로딩 스켈레톤, 오류 안내, 성공 테이블로 전환됩니다.
          </span>
        </div>
        <QueryResultPreview response={response} status={status} error={error} />
      </section>

      <section className="section-block history-section" aria-labelledby="history-title">
        <div className="section-heading">
          <p>05 · RECENT QUERY HISTORY</p>
          <h2 id="history-title">최근 성공 질문을 다시 실행</h2>
          <span>
            현재 화면에서 성공한 질문을 최대 3개까지 관리하고, 버튼 한 번으로 같은 요청을 다시
            실행할 수 있습니다.
          </span>
        </div>
        <QueryHistory entries={entries} disabled={isLoading} onRerun={run} />
      </section>

      <footer>
        <span>Day 04 complete</span>
        <span>Vite · React · TypeScript · ESLint</span>
      </footer>
    </main>
  )
}

export default App
