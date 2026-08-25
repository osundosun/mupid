import type { ApiResponse, QueryResult, RequestStatus } from '../types/query'
import { formatElapsedTime } from '../utils/formatElapsedTime'

interface QueryResultPreviewProps {
  response: ApiResponse<QueryResult>
  status: RequestStatus
}

export function QueryResultPreview({ response, status }: QueryResultPreviewProps) {
  const { data } = response

  return (
    <article className="result-card">
      <div className="result-toolbar">
        <div>
          <span className="query-label">QUESTION · {data.dbId}</span>
          <p className="query-text">{data.question}</p>
        </div>
        <div className="result-meta" aria-label="응답 메타 정보">
          <span className={status === 'success' ? 'success' : undefined}>{status}</span>
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
                  <tr key={`${String(row.department_name)}-${rowIndex}`}>
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
