import type { QueryResult } from '../types/query'

function escapeCsvCell(value: string | number | null): string {
  const normalized = value === null ? '' : String(value)
  return `"${normalized.replaceAll('"', '""')}"`
}

export function createResultCsv(result: QueryResult): string {
  const header = result.columns.map(escapeCsvCell).join(',')
  const rows = result.rows.map((row) =>
    result.columns.map((column) => escapeCsvCell(row[column] ?? null)).join(','),
  )

  return `\uFEFF${[header, ...rows].join('\n')}`
}

export function downloadResultCsv(result: QueryResult): void {
  const csv = createResultCsv(result)
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  const timestamp = new Date().toISOString().slice(0, 10)

  link.href = objectUrl
  link.download = `${result.dbId}-${timestamp}.csv`
  link.hidden = true
  document.body.append(link)
  link.click()
  link.remove()

  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
}
