import type { ApiResponse, QueryError, QueryResult, RequestStatus } from '../types/query'
import { formatElapsedTime } from '../utils/formatElapsedTime'

interface QueryResultPreviewProps {
  response: ApiResponse<QueryResult> | null
  status: RequestStatus
  error: QueryError | null
}

function ResultSkeleton() {
  return (
    <div className="result-skeleton" aria-hidden="true">
      <span className="skeleton-line wide" />
      <span className="skeleton-line" />
      <span className="skeleton-line narrow" />
      <span className="skeleton-block" />
    </div>
  )
}

function ResultPlaceholder({ title, description }: { title: string; description: string }) {
  return (
    <div className="result-placeholder">
      <h3>{title}</h3>
      <p>{description}</p>
    </div>
  )
}

export function QueryResultPreview({ response, status, error }: QueryResultPreviewProps) {
  if (status === 'loading') {
    return (
      <article className="result-card is-loading" aria-busy="true">
        <div className="result-toolbar">
          <div>
            <span className="query-label">GENERATING SQL</span>
            <p className="query-text">모델 응답을 기다리는 중입니다…</p>
          </div>
          <div className="result-meta">
            <span className="loading">loading</span>
          </div>
        </div>
        <ResultSkeleton />
      </article>
    )
  }

  if (status === 'error') {
    return (
      <article className="result-card is-error">
        <div className="result-toolbar">
          <div>
            <span className="query-label">REQUEST FAILED · {error?.code}</span>
            <p className="query-text">{error?.message}</p>
          </div>
          <div className="result-meta">
            <span className="error">error</span>
          </div>
        </div>
        <ResultPlaceholder
          title="표시할 결과가 없습니다"
          description="요청이 실패해 SQL과 결과 테이블을 받지 못했습니다. 위의 재시도 버튼으로 같은 질문을 다시 보낼 수 있습니다."
        />
      </article>
    )
  }

  if (!response) {
    return (
      <article className="result-card">
        <ResultPlaceholder
          title="아직 요청한 질문이 없습니다"
          description="데이터베이스와 자연어 질문을 입력하고 Generate SQL을 누르면 생성된 SQL과 결과 테이블이 이곳에 표시됩니다."
        />
      </article>
    )
  }

  const { data } = response
  // 취소 후에는 직전 성공 응답이 남아 있으므로 상태 칩을 previous로 구분한다.
  const metaStatus = status === 'idle' ? 'previous' : status

  return (
    <article className="result-card">
      <div className="result-toolbar">
        <div>
          <span className="query-label">QUESTION · {data.dbId}</span>
          <p className="query-text">{data.question}</p>
        </div>
        <div className="result-meta" aria-label="응답 메타 정보">
          <span className={status === 'success' ? 'success' : undefined}>{metaStatus}</span>
          <span>·</span>
          <span>{data.modelName}</span>
          <span>·</span>
          <span>{formatElapsedTime(data.elapsedTime)}</span>
        </div>
      </div>

      <div className="result-layout">
        <section className="sql-panel" aria-labelledby="sql-title">
          <h3 className="panel-title" id="sql-title">
            Generated SQL
          </h3>
          <pre>
            <code>{data.sql}</code>
          </pre>
        </section>

        <section className="table-panel" aria-labelledby="table-title">
          <h3 className="panel-title" id="table-title">
            Result · {data.rows.length} rows
          </h3>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  {data.columns.map((column) => (
                    <th key={column} scope="col">
                      {column}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.rows.map((row, rowIndex) => (
                  <tr key={`row-${rowIndex}`}>
                    {data.columns.map((column) => (
                      <td key={column}>{row[column] ?? '-'}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </article>
  )
}
