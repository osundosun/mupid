import { useCallback, useState } from 'react'
import type { ApiResponse, QueryHistoryEntry, QueryResult } from '../types/query'

const MAX_HISTORY_COUNT = 3

export function useQueryHistory() {
  const [entries, setEntries] = useState<QueryHistoryEntry[]>([])

  const add = useCallback((response: ApiResponse<QueryResult>) => {
    const entry: QueryHistoryEntry = {
      id: `${response.requestedAt}-${response.data.dbId}`,
      requestedAt: response.requestedAt,
      data: response.data,
    }

    setEntries((current) => {
      const withoutSameQuestion = current.filter(
        (item) =>
          item.data.dbId !== entry.data.dbId || item.data.question !== entry.data.question,
      )

      return [entry, ...withoutSameQuestion].slice(0, MAX_HISTORY_COUNT)
    })
  }, [])

  return { entries, add }
}
