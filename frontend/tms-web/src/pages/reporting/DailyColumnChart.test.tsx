import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DailyColumnChart } from './DailyColumnChart'

/**
 * The chart's one invariant: a day nobody measured and a day whose value is zero must not look the
 * same.
 *
 * Everything else here is geometry, and geometry that is slightly off is visible. This is not: an
 * unmeasured day painted as a zero column reads as "we were never punctual" instead of "we have no
 * evidence", and it is the exact claim the whole report is arranged to avoid making.
 */
function renderChart(points: { date: string; value: number | null }[]) {
  return render(
    <DailyColumnChart
      title="Salidas a tiempo por día"
      description="Sobre las salidas registradas de cada día."
      points={points}
      formatValue={(value) => `${value}%`}
      formatDate={(value) => value}
      noDataLabel="Sin datos"
      max={100}
    />,
  )
}

describe('DailyColumnChart', () => {
  it('bands a day that was never measured and says so in its tooltip', () => {
    const { container } = renderChart([{ date: '2026-03-01', value: null }])

    expect(container.querySelectorAll('.tms-chart-nodata')).toHaveLength(1)
    expect(container.querySelector('.tms-chart-column')).toBeNull()
    expect(container.textContent).toContain('2026-03-01 - Sin datos')
  })

  it('draws no band and no column for a day whose value is genuinely zero', () => {
    const { container } = renderChart([{ date: '2026-03-01', value: 0 }])

    // No band: the day was measured. No column: zero has no height. The tooltip is what tells the
    // two apart, and the detail table below the chart carries the value in full.
    expect(container.querySelectorAll('.tms-chart-nodata')).toHaveLength(0)
    expect(container.querySelector('.tms-chart-column')).toBeNull()
    expect(container.textContent).toContain('2026-03-01 - 0%')
  })

  it('draws one column per measured day, with a hit target for every day either way', () => {
    const { container } = renderChart([
      { date: '2026-03-01', value: 80 },
      { date: '2026-03-02', value: null },
      { date: '2026-03-03', value: 100 },
    ])

    expect(container.querySelectorAll('.tms-chart-column')).toHaveLength(2)
    // The hit target spans the whole day's slot, so a one-pixel column on a ninety-day range is
    // still hoverable - and an unmeasured day is still explicable.
    expect(container.querySelectorAll('.tms-chart-hit')).toHaveLength(3)
  })

  it('names the series in its caption, because a single-series chart carries no legend', () => {
    const { getByText } = renderChart([{ date: '2026-03-01', value: 80 }])

    expect(getByText('Salidas a tiempo por día')).toBeInTheDocument()
  })
})
