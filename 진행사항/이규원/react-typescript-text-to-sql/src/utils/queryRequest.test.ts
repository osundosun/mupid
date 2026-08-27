import { describe, expect, it } from 'vitest'
import { normalizeQueryRequest, validateQueryRequest } from './queryRequest'

describe('queryRequest', () => {
  it('질문 앞뒤의 공백을 제거한다', () => {
    expect(
      normalizeQueryRequest({ dbId: 'concert_singer', question: '  Show all singers  ' }),
    ).toEqual({ dbId: 'concert_singer', question: 'Show all singers' })
  })

  it('데이터베이스를 선택하지 않으면 오류를 반환한다', () => {
    const errors = validateQueryRequest({ dbId: '', question: 'Show all singers' })

    expect(errors.dbId).toBe('데이터베이스를 선택해 주세요.')
  })

  it('공백으로만 구성된 질문을 거부한다', () => {
    const errors = validateQueryRequest({ dbId: 'concert_singer', question: '   ' })

    expect(errors.question).toBe('자연어 질문을 입력해 주세요.')
  })

  it('유효한 요청에는 오류를 반환하지 않는다', () => {
    const errors = validateQueryRequest({
      dbId: 'concert_singer',
      question: 'Show all singers',
    })

    expect(errors).toEqual({})
  })
})
