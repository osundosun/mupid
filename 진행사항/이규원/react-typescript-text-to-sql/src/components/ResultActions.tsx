import { useState } from 'react'
import type { QueryResult } from '../types/query'

type CopyStatus = 'idle' | 'copied' | 'error'

interface ResultActionsProps {
  result: QueryResult
}

export function ResultActions({ result }: ResultActionsProps) {
  const [copyStatus, setCopyStatus] = useState<CopyStatus>('idle')

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(result.sql)
      setCopyStatus('copied')
    } catch {
      setCopyStatus('error')
    }
  }

  return (
    <div className="result-actions" aria-label="결과 활용 기능">
      <span className="copy-feedback" role="status" aria-live="polite">
        {copyStatus === 'error' ? '복사하지 못했습니다.' : ''}
      </span>
      <button className="action-button" type="button" onClick={handleCopy}>
        {copyStatus === 'copied' ? 'Copied!' : 'Copy SQL'}
      </button>
    </div>
  )
}
