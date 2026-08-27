import { describe, expect, it } from 'vitest'
import type { QueryHistoryEntry } from '../types/query'
import { addQueryHistoryEntry } from './queryHistory'

function createEntry(id: string, question = `question-${id}`): QueryHistoryEntry {
  return {
    id,
    requestedAt: `2026-08-28T09:0${id}:00+09:00`,
    data: {
      dbId: 'concert_singer',
      question,
      sql: 'SELECT 1;',
      columns: ['value'],
      rows: [{ value: 1 }],
      elapsedTime: 0.9,
      modelName: 'Mock Text-to-SQL',
    },
  }
}

describe('addQueryHistoryEntry', () => {
  it('새 질문을 목록의 맨 앞에 추가한다', () => {
    const entries = addQueryHistoryEntry([createEntry('1')], createEntry('2'))

    expect(entries.map((entry) => entry.id)).toEqual(['2', '1'])
  })

  it('같은 DB와 질문은 최신 항목 하나만 유지한다', () => {
    const entries = addQueryHistoryEntry(
      [createEntry('1', 'same question')],
      createEntry('2', 'same question'),
    )

    expect(entries).toHaveLength(1)
    expect(entries[0].id).toBe('2')
  })

  it('최근 질문을 최대 3개까지 유지한다', () => {
    const current = [createEntry('3'), createEntry('2'), createEntry('1')]
    const entries = addQueryHistoryEntry(current, createEntry('4'))

    expect(entries.map((entry) => entry.id)).toEqual(['4', '3', '2'])
  })
})
