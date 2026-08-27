import { useCallback, useState } from 'react'
import type { ApiResponse, QueryHistoryEntry, QueryResult } from '../types/query'
import { addQueryHistoryEntry, createQueryHistoryEntry } from '../utils/queryHistory'

export function useQueryHistory() {
  const [entries, setEntries] = useState<QueryHistoryEntry[]>([])

  const add = useCallback((response: ApiResponse<QueryResult>) => {
    const entry = createQueryHistoryEntry(response)
    setEntries((current) => addQueryHistoryEntry(current, entry))
  }, [])

  return { entries, add }
}
