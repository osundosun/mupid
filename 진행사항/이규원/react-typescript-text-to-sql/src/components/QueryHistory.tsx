import type { QueryHistoryEntry, QueryRequest } from '../types/query'

interface QueryHistoryProps {
  entries: QueryHistoryEntry[]
  disabled?: boolean
  onRerun: (request: QueryRequest) => void
  onRemove: (id: string) => void
  onClear: () => void
}

function formatRequestedAt(requestedAt: string): string {
  const date = new Date(requestedAt)

  if (Number.isNaN(date.getTime())) {
    return requestedAt
  }

  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

export function QueryHistory({
  entries,
  disabled = false,
  onRerun,
  onRemove,
  onClear,
}: QueryHistoryProps) {
  if (entries.length === 0) {
    return (
      <div className="history-empty">
        <span>NO RECENT QUERIES</span>
        <h3>성공한 질문이 여기에 쌓입니다</h3>
        <p>질문을 실행하면 최근 기록을 최대 5개까지 브라우저에 저장합니다.</p>
      </div>
    )
  }

  return (
    <div className="history-card">
      <div className="history-toolbar">
        <div>
          <span>LOCAL HISTORY</span>
          <strong>{entries.length} / 5 saved</strong>
        </div>
        <button className="history-clear" type="button" disabled={disabled} onClick={onClear}>
          Clear all
        </button>
      </div>

      <ol className="history-list">
        {entries.map((entry) => (
          <li key={entry.id}>
            <div className="history-main">
              <div className="history-meta">
                <span>{entry.data.dbId}</span>
                <time dateTime={entry.requestedAt}>{formatRequestedAt(entry.requestedAt)}</time>
              </div>
              <p>{entry.data.question}</p>
              <code>{entry.data.sql}</code>
            </div>
            <div className="history-actions">
              <button
                className="history-run"
                type="button"
                disabled={disabled}
                onClick={() =>
                  onRerun({ dbId: entry.data.dbId, question: entry.data.question })
                }
              >
                Run again
              </button>
              <button
                className="history-remove"
                type="button"
                disabled={disabled}
                aria-label={`${entry.data.question} 기록 삭제`}
                onClick={() => onRemove(entry.id)}
              >
                Remove
              </button>
            </div>
          </li>
        ))}
      </ol>
    </div>
  )
}
