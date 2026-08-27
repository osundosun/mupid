import { useCallback, useEffect, useState } from 'react'
import type { ApiResponse, QueryHistoryEntry, QueryResult } from '../types/query'

const STORAGE_KEY = 'text-to-sql-query-history-v1'
const MAX_HISTORY_COUNT = 5

function isHistoryEntry(value: unknown): value is QueryHistoryEntry {
  if (!value || typeof value !== 'object') {
    return false
  }

  const candidate = value as Partial<QueryHistoryEntry>

  return (
    typeof candidate.id === 'string' &&
    typeof candidate.requestedAt === 'string' &&
    Boolean(candidate.data) &&
    typeof candidate.data?.dbId === 'string' &&
    typeof candidate.data?.question === 'string' &&
    typeof candidate.data?.sql === 'string' &&
    Array.isArray(candidate.data?.columns) &&
    Array.isArray(candidate.data?.rows)
  )
}

function readStoredHistory(): QueryHistoryEntry[] {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)

    if (!stored) {
      return []
    }

    const parsed: unknown = JSON.parse(stored)

    return Array.isArray(parsed) ? parsed.filter(isHistoryEntry).slice(0, MAX_HISTORY_COUNT) : []
  } catch {
    return []
  }
}

export function useQueryHistory() {
  const [entries, setEntries] = useState<QueryHistoryEntry[]>(readStoredHistory)

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(entries))
    } catch {
      // 저장 공간 제한이나 브라우저 정책으로 실패해도 현재 화면의 기록은 유지한다.
    }
  }, [entries])

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

  const remove = useCallback((id: string) => {
    setEntries((current) => current.filter((entry) => entry.id !== id))
  }, [])

  const clear = useCallback(() => {
    setEntries([])
  }, [])

  return { entries, add, remove, clear }
}
