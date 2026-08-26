import type { ChangeEvent } from 'react'
import type { DatabaseOption } from '../types/query'

interface DatabaseSelectProps {
  options: DatabaseOption[]
  value: string
  disabled?: boolean
  onChange: (dbId: string) => void
}

export function DatabaseSelect({
  options,
  value,
  disabled = false,
  onChange,
}: DatabaseSelectProps) {
  const selectedDatabase = options.find((option) => option.id === value)

  function handleChange(event: ChangeEvent<HTMLSelectElement>) {
    onChange(event.target.value)
  }

  return (
    <div className="field-group">
      <label htmlFor="database">Database</label>
      <select id="database" value={value} disabled={disabled} onChange={handleChange}>
        {options.map((option) => (
          <option key={option.id} value={option.id}>
            {option.label}
          </option>
        ))}
      </select>
      <p className="field-help">{selectedDatabase?.description}</p>
    </div>
  )
}
