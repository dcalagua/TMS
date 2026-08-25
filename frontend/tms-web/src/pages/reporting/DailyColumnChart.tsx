/**
 * A single-series column chart over a run of consecutive days, drawn as inline SVG.
 *
 * <b>Why inline SVG and not a chart library.</b> The product ships no charting dependency and this
 * is the only chart in it. What is needed here is a hundred lines of geometry; a library would be
 * a hundred kilobytes, a second theming system to keep in step with `tokens.css`, and a second
 * place dark mode can go wrong.
 *
 * <b>One series per chart, and never two scales on one plot.</b> The report shows counts and
 * percentages, and putting them on one plot with two y-axes is the classic way a chart invents a
 * correlation that is not in the data: the alignment of the two scales is arbitrary. So the screen
 * stacks two of these instead, sharing a filter and a range but not an axis. One series also means
 * no legend - the heading says what is plotted, and a box with one swatch would only repeat it.
 *
 * <b>A blank day and a zero day are not the same thing, and do not look the same.</b> A day whose
 * value is `null` was never measured - no departure recorded, nothing delivered - and gets a muted
 * band behind it saying so. A day whose value is genuinely `0` gets no band and no column, which is
 * what zero looks like against a baseline. Painting `null` as 0 is the single most misleading thing
 * this chart could do, and the reason `KpiRate` sends null in the first place.
 *
 * <b>The tooltip enhances; it never gates.</b> Every value here is also a row of the detail table
 * below the charts, which is the accessible and printable twin. The `<title>` per column is a
 * mouse affordance on top of that, with a hit area that spans the whole day's slot rather than the
 * column's own width.
 */

interface DailyPoint {
  /** ISO `yyyy-mm-dd`. */
  date: string
  /** `null` means nothing was measured that day - not zero. */
  value: number | null
}

interface DailyColumnChartProps {
  /** Names the series; there is no legend, so this has to. */
  title: string
  /** One line saying what is plotted and what a blank day means. */
  description: string
  points: DailyPoint[]
  /** Already locale-aware. Used on the axis and in the tooltip. */
  formatValue: (value: number) => string
  formatDate: (date: string) => string
  /** What the tooltip says on a day with no measurement. */
  noDataLabel: string
  /**
   * A fixed top of scale - 100 for a percentage, so two ranges are comparable and a quiet month
   * does not magnify its own noise. Omitted, the scale grows to the data.
   */
  max?: number
}

/**
 * The drawing box. A fixed viewBox scaled to the container with `meet`, so the chart is responsive
 * without the text stretching - which is what `preserveAspectRatio="none"` would do to it.
 */
const VIEW_WIDTH = 1000
const VIEW_HEIGHT = 210

/** Room for the y-axis ticks on the left and for the date labels in the band below the plot. */
const PADDING = { top: 10, right: 6, bottom: 24, left: 44 }

const PLOT_WIDTH = VIEW_WIDTH - PADDING.left - PADDING.right
const PLOT_HEIGHT = VIEW_HEIGHT - PADDING.top - PADDING.bottom
const BASELINE = PADDING.top + PLOT_HEIGHT

/** Capped rather than filling the slot: the leftover is the air that makes columns read as columns. */
const MAX_COLUMN_WIDTH = 24

/** The surface gap that separates touching columns. Never a stroke - see the design notes. */
const COLUMN_GAP = 2

/** The rounded data-end. Square at the baseline, because that end is a measurement, not a shape. */
const CORNER_RADIUS = 4

/**
 * The gridlines, as their step indexes. Five including the baseline: enough to read a value off,
 * few enough to stay recessive. Written out rather than generated so the scale can be built to
 * divide by it exactly - see `niceScaleMax`.
 */
const GRID_LINES = [0, 1, 2, 3, 4]
const GRID_STEPS = GRID_LINES.length - 1

/** How many date labels the x-axis carries at most, whatever the range length. */
const MAX_DATE_LABELS = 7

export function DailyColumnChart({
  title,
  description,
  points,
  formatValue,
  formatDate,
  noDataLabel,
  max,
}: DailyColumnChartProps) {
  // A scale that never collapses: a range in which every value is zero still needs an axis, and
  // dividing by its maximum would put every column at the top of the chart.
  const dataMax = points.reduce((highest, point) => Math.max(highest, point.value ?? 0), 0)
  const scaleMax = max ?? niceScaleMax(dataMax)

  const slot = PLOT_WIDTH / Math.max(points.length, 1)
  const columnWidth = Math.max(1, Math.min(MAX_COLUMN_WIDTH, slot - COLUMN_GAP))
  const labelEvery = Math.max(1, Math.ceil(points.length / MAX_DATE_LABELS))

  return (
    <figure className="tms-chart">
      <figcaption className="tms-chart-caption">
        <span className="tms-chart-title">{title}</span>
        <span className="tms-chart-description">{description}</span>
      </figcaption>
      <svg
        className="tms-chart-canvas"
        viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
        preserveAspectRatio="xMidYMid meet"
        role="img"
        aria-label={title}
      >
        {/* Gridlines first, so every mark sits on top of them. Solid hairlines one step off the
            surface: dashing would read as a threshold when it is only a grid. */}
        {GRID_LINES.map((step) => {
          const y = BASELINE - (PLOT_HEIGHT / GRID_STEPS) * step
          return (
            <g key={step}>
              <line className="tms-chart-grid" x1={PADDING.left} y1={y} x2={VIEW_WIDTH - PADDING.right} y2={y} />
              <text className="tms-chart-tick" x={PADDING.left - 8} y={y + 4} textAnchor="end">
                {formatValue((scaleMax / GRID_STEPS) * step)}
              </text>
            </g>
          )
        })}

        {points.map((point, index) => {
          const slotX = PADDING.left + slot * index
          const columnX = slotX + (slot - columnWidth) / 2
          const value = point.value
          const height = value === null ? 0 : (Math.min(value, scaleMax) / scaleMax) * PLOT_HEIGHT

          return (
            <g key={point.date}>
              {/* The unmeasured band. Behind the plot rather than at the baseline, so it can never
                  be read as a very small value. */}
              {value === null && (
                <rect className="tms-chart-nodata" x={slotX} y={PADDING.top} width={slot} height={PLOT_HEIGHT} />
              )}
              {height > 0 && <path className="tms-chart-column" d={columnPath(columnX, columnWidth, height)} />}
              {/* The hit target: the whole day's slot, full height, so a one-pixel column on a
                  ninety-day range is still hoverable. */}
              <rect className="tms-chart-hit" x={slotX} y={PADDING.top} width={slot} height={PLOT_HEIGHT}>
                <title>
                  {`${formatDate(point.date)} - ${value === null ? noDataLabel : formatValue(value)}`}
                </title>
              </rect>
            </g>
          )
        })}

        {points.map((point, index) =>
          index % labelEvery === 0 ? (
            <text
              key={`label-${point.date}`}
              className="tms-chart-tick"
              x={PADDING.left + slot * index + slot / 2}
              y={BASELINE + 16}
              textAnchor="middle"
            >
              {formatDate(point.date)}
            </text>
          ) : null,
        )}
      </svg>
    </figure>
  )
}

/**
 * A column: square where it meets the baseline, rounded at the data end.
 *
 * The radius shrinks for a column narrower or shorter than it, so a thin column on a long range
 * degrades into a plain rectangle instead of into a lozenge that overstates its own height.
 */
function columnPath(x: number, width: number, height: number): string {
  const radius = Math.min(CORNER_RADIUS, width / 2, height)
  const top = BASELINE - height
  return [
    `M ${x} ${BASELINE}`,
    `L ${x} ${top + radius}`,
    `Q ${x} ${top} ${x + radius} ${top}`,
    `L ${x + width - radius} ${top}`,
    `Q ${x + width} ${top} ${x + width} ${top + radius}`,
    `L ${x + width} ${BASELINE}`,
    'Z',
  ].join(' ')
}

/**
 * A top of scale that divides into `GRID_STEPS` round ticks - 0 / 5 / 10 / 15 / 20 rather than
 * 0 / 4.25 / 8.5. Ticks nobody can read are ticks that force every column to be labelled instead.
 *
 * <b>The tick is a whole number, and at least 1.</b> Every series that scales itself here is a count
 * (shipments, problems), so 2.5 of a shipment is not a gridline anybody wants - which is why 2.5 is
 * absent from the multiples below, at the cost of a little more headroom. A series that is genuinely
 * fractional passes an explicit `max`, as the percentage chart does with 100. The floor of 1 is also
 * what keeps an empty range - a `highest` of zero - from producing a scale of zero and a division
 * by it.
 */
function niceScaleMax(highest: number): number {
  const rough = Math.max(highest, 1) / GRID_STEPS
  const magnitude = 10 ** Math.floor(Math.log10(rough))
  const step = [1, 2, 5, 10]
    .map((multiple) => multiple * magnitude)
    .find((candidate) => candidate >= rough)
  return Math.max(Math.ceil(step ?? magnitude * 10), 1) * GRID_STEPS
}
