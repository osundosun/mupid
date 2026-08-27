import type { ApiResponse, QueryHistoryEntry, QueryResult } from '../types/query'

export const MAX_HISTORY_COUNT = 3

export function createQueryHistoryEntry(
  response: ApiResponse<QueryResult>,
): QueryHistoryEntry {
  return {
    id: `${response.requestedAt}-${response.data.dbId}`,
    requestedAt: response.requestedAt,
    data: response.data,
  }
}

export function addQueryHistoryEntry(
  entries: QueryHistoryEntry[],
  entry: QueryHistoryEntry,
): QueryHistoryEntry[] {
  const withoutSameQuestion = entries.filter(
    (item) =>
      item.data.dbId !== entry.data.dbId || item.data.question !== entry.data.question,
  )

  return [entry, ...withoutSameQuestion].slice(0, MAX_HISTORY_COUNT)
}
