import { describe, expect, it } from 'vitest'
import { formatElapsedTime } from './formatElapsedTime'

describe('formatElapsedTime', () => {
  it('응답 시간을 소수점 둘째 자리 초 단위로 표시한다', () => {
    expect(formatElapsedTime(1.236)).toBe('1.24s')
  })

  it('0초도 같은 형식으로 표시한다', () => {
    expect(formatElapsedTime(0)).toBe('0.00s')
  })
})
