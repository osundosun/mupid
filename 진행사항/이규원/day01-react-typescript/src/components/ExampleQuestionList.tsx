import type { ExampleQuestion } from '../types/query'

interface ExampleQuestionListProps {
  examples: ExampleQuestion[]
  disabled?: boolean
  onSelect: (example: ExampleQuestion) => void
}

export function ExampleQuestionList({
  examples,
  disabled = false,
  onSelect,
}: ExampleQuestionListProps) {
  return (
    <div className="example-list" aria-label="예시 질문">
      <span>Example questions</span>
      <div>
        {examples.map((example) => (
          <button
            key={example.id}
            type="button"
            disabled={disabled}
            onClick={() => onSelect(example)}
          >
            {example.label}
          </button>
        ))}
      </div>
    </div>
  )
}
