import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LookupField, type LookupOption } from './LookupField'

const LIMA: LookupOption = { id: 'origin-1', code: 'LIM-01', name: 'Lima warehouse' }
const AREQUIPA: LookupOption = { id: 'origin-2', code: 'AQP-01', name: 'Arequipa warehouse' }

const search = vi.fn<(term: string, signal: AbortSignal) => Promise<LookupOption[]>>()

/** A minimal owner, because the field is controlled: the tests are about what it hands back,
 * and a component that never re-renders with the new selection could not show it. */
function Host({ initial = null, onPick }: { initial?: LookupOption | null; onPick?: (o: LookupOption | null) => void }) {
  const [selected, setSelected] = useState<LookupOption | null>(initial)
  return (
    <>
      <label htmlFor="lookup">Origen</label>
      <LookupField
        id="lookup"
        value={selected?.id ?? ''}
        selected={selected}
        queryKey={['test-lookup']}
        search={search}
        placeholder="Escribe un código"
        onChange={(option) => {
          setSelected(option)
          onPick?.(option)
        }}
      />
    </>
  )
}

function renderField(props: Parameters<typeof Host>[0] = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <Host {...props} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('LookupField', () => {
  it('asks nothing until it is opened', () => {
    search.mockResolvedValue([])
    renderField()

    expect(search).not.toHaveBeenCalled()
  })

  it('lists what the server returns and hands the whole record back on selection', async () => {
    search.mockResolvedValue([LIMA, AREQUIPA])
    const onPick = vi.fn()
    renderField({ onPick })

    await userEvent.click(screen.getByLabelText('Origen'))
    await userEvent.click(await screen.findByRole('option', { name: /Lima warehouse/ }))

    expect(onPick).toHaveBeenCalledWith(LIMA)
    // Closed, the field reads as the selection rather than as what was typed.
    expect(screen.getByLabelText('Origen')).toHaveValue('LIM-01 — Lima warehouse')
  })

  it('sends the typed term to the server once typing stops', async () => {
    search.mockResolvedValue([AREQUIPA])
    renderField()

    await userEvent.click(screen.getByLabelText('Origen'))
    await userEvent.type(screen.getByLabelText('Origen'), 'AQP')

    await waitFor(() => expect(search).toHaveBeenCalledWith('AQP', expect.anything()))
    // Debounced: the four opening/keystroke renders do not become four requests.
    expect(search.mock.calls.filter(([term]) => term === 'AQ')).toHaveLength(0)
    expect(await screen.findByRole('option', { name: /Arequipa warehouse/ })).toBeInTheDocument()
  })

  it('selects nothing when the term matches nothing', async () => {
    search.mockResolvedValue([])
    const onPick = vi.fn()
    renderField({ onPick })

    await userEvent.click(screen.getByLabelText('Origen'))
    await userEvent.type(screen.getByLabelText('Origen'), 'ZZZ')

    expect(await screen.findByText('Sin coincidencias')).toBeInTheDocument()
    await userEvent.keyboard('{Enter}')
    expect(onPick).not.toHaveBeenCalled()
  })

  it('moves through the list with the arrow keys and picks with Enter', async () => {
    search.mockResolvedValue([LIMA, AREQUIPA])
    const onPick = vi.fn()
    renderField({ onPick })

    await userEvent.click(screen.getByLabelText('Origen'))
    await screen.findByRole('option', { name: /Lima warehouse/ })
    await userEvent.keyboard('{ArrowDown}{Enter}')

    expect(onPick).toHaveBeenCalledWith(AREQUIPA)
  })

  it('clears the selection', async () => {
    search.mockResolvedValue([LIMA])
    const onPick = vi.fn()
    renderField({ initial: LIMA, onPick })

    await userEvent.click(screen.getByRole('button', { name: 'Limpiar' }))

    expect(onPick).toHaveBeenCalledWith(null)
    expect(screen.getByLabelText('Origen')).toHaveValue('')
  })

  it('keeps showing an assigned master the search would no longer return', async () => {
    // The active-only search cannot find a deactivated origin, but an order that already
    // references one must still show which one - not an empty field the operator would refill.
    search.mockResolvedValue([])
    renderField({ initial: { id: 'origin-9', code: 'OLD-01', name: 'Retired depot' } })

    expect(screen.getByLabelText('Origen')).toHaveValue('OLD-01 — Retired depot')
  })
})
