import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { QueryForm } from './QueryForm'

describe('QueryForm', () => {
  it('공백 질문을 제출하면 오류 메시지를 표시한다', async () => {
    const onSubmit = vi.fn()
    render(
      <QueryForm
        initialValues={{ dbId: 'concert_singer', question: '   ' }}
        onSubmit={onSubmit}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Generate SQL' }))

    expect(screen.getByRole('alert')).toHaveTextContent('자연어 질문을 입력해 주세요.')
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('유효한 질문은 공백을 정리한 뒤 요청으로 전달한다', async () => {
    const onSubmit = vi.fn()
    render(
      <QueryForm
        initialValues={{ dbId: 'concert_singer', question: '  Show all singers  ' }}
        onSubmit={onSubmit}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Generate SQL' }))

    expect(onSubmit).toHaveBeenCalledWith({
      dbId: 'concert_singer',
      question: 'Show all singers',
    })
  })
})
