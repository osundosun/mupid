import type { QueryErrorCode } from '../types/query'

/**
 * 실제 Text-to-SQL 서버가 없기 때문에 요청 상태 UI를 학습용으로 재현한다.
 * 매 3번째 요청을 실패시켜 로딩 → 오류 → 재시도 흐름을 항상 확인할 수 있게 한다.
 */
export const REQUEST_DELAY_MS = 1500

export const FAILURE_INTERVAL = 3

const failureCodes: QueryErrorCode[] = ['network', 'server', 'timeout']

export function shouldFailAttempt(attempt: number): boolean {
  return attempt % FAILURE_INTERVAL === 0
}

export function failureCodeForAttempt(attempt: number): QueryErrorCode {
  const index = Math.floor(attempt / FAILURE_INTERVAL - 1) % failureCodes.length

  return failureCodes[index] ?? 'server'
}
