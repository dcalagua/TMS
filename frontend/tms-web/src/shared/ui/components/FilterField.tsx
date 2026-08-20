import { useId, type ReactNode } from 'react'

interface FilterFieldProps {
  label: string
  /** Receives the id to put on the control, so the label points at the real element. */
  children: (id: string) => ReactNode
}

/**
 * One labelled control inside the filter bar.
 *
 * It exists because every list screen was writing the same `<div><label htmlFor>…</label>
 * <input class="form-control form-control-sm"></div>` by hand, and "the same" was already
 * drifting: a missing `htmlFor` here, a different size class there. The label/control pairing
 * and the id wiring are the parts that are easy to get subtly wrong, so they live in one place.
 *
 * The control is a render prop rather than a `type` union: filters are inputs, selects, date
 * ranges and combinations of them, and enumerating those would turn this into a form library.
 */
export function FilterField({ label, children }: FilterFieldProps) {
  const id = useId()
  return (
    <div className="tms-filter-field">
      <label htmlFor={id}>{label}</label>
      {children(id)}
    </div>
  )
}
