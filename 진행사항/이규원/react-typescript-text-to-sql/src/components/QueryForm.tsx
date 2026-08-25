import { useState } from 'react'
import type { ChangeEvent, FormEvent } from 'react'
import { databaseOptions, exampleQuestions } from '../mocks/queryForm'
import type { ExampleQuestion, QueryRequest } from '../types/query'
import { DatabaseSelect } from './DatabaseSelect'
import { ExampleQuestionList } from './ExampleQuestionList'

const MAX_QUESTION_LENGTH = 240

interface QueryFormProps {
  initialValues: QueryRequest
  isSubmitting?: boolean
  onSubmit: (request: QueryRequest) => void
}

interface QueryFormErrors {
  dbId?: string
  question?: string
}

export function QueryForm({
  initialValues,
  isSubmitting = false,
  onSubmit,
}: QueryFormProps) {
  const [values, setValues] = useState<QueryRequest>(initialValues)
  const [errors, setErrors] = useState<QueryFormErrors>({})

  function validate(request: QueryRequest): QueryFormErrors {
    const nextErrors: QueryFormErrors = {}
    const trimmedQuestion = request.question.trim()

    if (!request.dbId) {
      nextErrors.dbId = '데이터베이스를 선택해 주세요.'
    }

    if (!trimmedQuestion) {
      nextErrors.question = '자연어 질문을 입력해 주세요.'
    } else if (trimmedQuestion.length < 10) {
      nextErrors.question = '질문은 10자 이상 입력해 주세요.'
    } else if (request.question.length > MAX_QUESTION_LENGTH) {
      nextErrors.question = `질문은 ${MAX_QUESTION_LENGTH}자 이하로 입력해 주세요.`
    }

    return nextErrors
  }

  function handleDatabaseChange(dbId: string) {
    setValues((current) => ({ ...current, dbId }))
    setErrors((current) => ({ ...current, dbId: undefined }))
  }

  function handleQuestionChange(event: ChangeEvent<HTMLTextAreaElement>) {
    setValues((current) => ({ ...current, question: event.target.value }))
    setErrors((current) => ({ ...current, question: undefined }))
  }

  function handleExampleSelect(example: ExampleQuestion) {
    setValues({ dbId: example.dbId, question: example.question })
    setErrors({})
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const nextRequest = {
      dbId: values.dbId,
      question: values.question.trim(),
    }
    const nextErrors = validate(nextRequest)

    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    onSubmit(nextRequest)
  }

  const isQuestionTooLong = values.question.length > MAX_QUESTION_LENGTH

  return (
    <form className="query-form" noValidate onSubmit={handleSubmit}>
      <div className="form-header">
        <div>
          <span>Skeleton-2 request</span>
          <h3>Ask the database</h3>
        </div>
        <span className="contract-badge">db_id · question → query</span>
      </div>

      <DatabaseSelect
        options={databaseOptions}
        value={values.dbId}
        disabled={isSubmitting}
        onChange={handleDatabaseChange}
      />
      {errors.dbId && <p className="field-error">{errors.dbId}</p>}

      <div className="field-group">
        <div className="field-label-row">
          <label htmlFor="question">Natural language question</label>
          <span className={isQuestionTooLong ? 'character-count error' : 'character-count'}>
            {values.question.length}/{MAX_QUESTION_LENGTH}
          </span>
        </div>
        <textarea
          id="question"
          rows={5}
          value={values.question}
          disabled={isSubmitting}
          aria-invalid={Boolean(errors.question)}
          aria-describedby={errors.question ? 'question-error' : 'question-help'}
          placeholder="e.g. How many heads of the departments are older than 56?"
          onChange={handleQuestionChange}
        />
        {errors.question ? (
          <p className="field-error" id="question-error" role="alert">
            {errors.question}
          </p>
        ) : (
          <p className="field-help" id="question-help">
            Skeleton 데이터셋의 질문 형식에 맞춰 영어 예시를 제공합니다.
          </p>
        )}
      </div>

      <ExampleQuestionList
        examples={exampleQuestions}
        disabled={isSubmitting}
        onSelect={handleExampleSelect}
      />

      <div className="form-actions">
        <p>현재는 입력 계약을 확인하기 위한 Mock 응답을 사용합니다.</p>
        <button
          className="submit-button"
          type="submit"
          disabled={isSubmitting || isQuestionTooLong}
        >
          {isSubmitting ? 'Generating…' : 'Generate SQL'}
        </button>
      </div>
    </form>
  )
}
