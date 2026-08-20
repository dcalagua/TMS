import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchDestinations } from '../../shared/api/destinationsApi'
import { fetchOrigins } from '../../shared/api/originsApi'
import {
  ORDER_PRIORITIES,
  createOrder,
  fetchOrder,
  updateOrder,
  type OrderDetailView,
  type OrderPriority,
  type OrderRequest,
} from '../../shared/api/ordersApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { FormField } from '../../shared/ui/components/FormField'
import { LoadingState } from '../../shared/ui/components/LoadingState'
import { LookupField, type LookupOption } from '../../shared/ui/components/LookupField'
import { StatusBadge } from '../../shared/ui/components/StatusBadge'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'order-form'

/** How many masters one lookup keystroke asks for. A page, not a catalogue: the operator is
 * narrowing by typing, and a list longer than this is a sign the term is still too broad. */
const LOOKUP_PAGE_SIZE = 20

interface OrderLineFormValues {
  materialCode: string
  materialDescription: string
  quantity: string
  uom: string
  unitWeightKg: string
  unitVolumeM3: string
  palletQuantity: string
}

interface OrderFormValues {
  externalSource: string
  externalReference: string
  originId: string
  destinationId: string
  customerName: string
  customerReference: string
  serviceDate: string
  priority: OrderPriority
  requestedWindowStart: string
  requestedWindowEnd: string
  declaredWeightKg: string
  declaredVolumeM3: string
  declaredPallets: string
  lines: OrderLineFormValues[]
}

interface OrderFormDrawerProps {
  companyId: string
  /** `null` creates a new order; otherwise the modal loads and edits/views this order's full
   * detail (including lines) - the list row alone (`OrderView`) does not carry them. */
  orderId: string | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof OrderFormValues>([
  'externalSource', 'externalReference', 'originId', 'destinationId', 'customerName', 'customerReference',
  'serviceDate', 'priority', 'requestedWindowStart', 'requestedWindowEnd',
  'declaredWeightKg', 'declaredVolumeM3', 'declaredPallets',
])

const BLANK_LINE: OrderLineFormValues = {
  materialCode: '', materialDescription: '', quantity: '1', uom: 'EA', unitWeightKg: '', unitVolumeM3: '', palletQuantity: '',
}

/** How the order's current origin/destination reads in its lookup before anything is typed.
 * Taken from the order itself rather than re-fetched, and kept even when the master has since
 * been deactivated - the same "deactivating does not silently break the editor" invariant the
 * old select's `withCurrentValue` provided. */
function assignedOption(id: string | null, code: string | null, name: string | null): LookupOption | null {
  if (id === null || id === '') return null
  return { id, code: code ?? id, name: name ?? code ?? id }
}

function toNumberOrUndefined(value: string): number | undefined {
  return value.trim() === '' ? undefined : Number(value)
}

/** A declared figure as the API wants it: `null` for "not stated", never `0` for it. The
 * distinction is the whole point of the declared columns - see `docs/domain/ORDER_TOTALS_V1.md`. */
function toDeclared(value: string): number | null {
  return toNumberOrUndefined(value) ?? null
}

function fromDeclared(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value)
}

/**
 * Client-side preview only, mirroring `OrderTotals.resolve` and `TransportOrderLine.applyInput`
 * - never sent to the backend and never trusted as the authoritative total. The server always
 * recomputes and returns the real totals (`OrderDetailView.totalWeightKg`/...) after save.
 *
 * The rule it reproduces (`docs/domain/ORDER_TOTALS_V1.md`): with lines, each measure is their
 * sum, falling back to the declared figure for a measure not a single line describes; with no
 * lines at all, the declared figures stand on their own. Reproduced here rather than only shown
 * after saving so that an operator can see, while typing, which of the two numbers in front of
 * them is the one that will be planned.
 */
function previewTotals(lines: OrderLineFormValues[], declared: DeclaredFormValues) {
  let weight: number | null = null
  let volume: number | null = null
  let pallets: number | null = null
  for (const line of lines) {
    const quantity = Number(line.quantity) || 0
    const unitWeight = toNumberOrUndefined(line.unitWeightKg)
    const unitVolume = toNumberOrUndefined(line.unitVolumeM3)
    const palletQuantity = toNumberOrUndefined(line.palletQuantity)
    if (unitWeight !== undefined) weight = (weight ?? 0) + quantity * unitWeight
    if (unitVolume !== undefined) volume = (volume ?? 0) + quantity * unitVolume
    if (palletQuantity !== undefined) pallets = (pallets ?? 0) + palletQuantity
  }

  const declaredWeight = toNumberOrUndefined(declared.declaredWeightKg)
  const declaredVolume = toNumberOrUndefined(declared.declaredVolumeM3)
  const declaredPallets = toNumberOrUndefined(declared.declaredPallets)

  return {
    calculated: lines.length > 0,
    weight: weight ?? declaredWeight ?? 0,
    volume: volume ?? declaredVolume ?? 0,
    pallets: pallets ?? declaredPallets ?? 0,
  }
}

/** Just the declared inputs, so `previewTotals` reads three fields rather than the whole form. */
type DeclaredFormValues = Pick<OrderFormValues, 'declaredWeightKg' | 'declaredVolumeM3' | 'declaredPallets'>

export function OrderFormDrawer({ companyId, orderId, onClose, onSaved }: OrderFormDrawerProps) {
  const { t } = useTranslation('orders')
  const { t: tc } = useTranslation('common')

  const orderQuery = useQuery({
    queryKey: ['order', companyId, orderId],
    queryFn: ({ signal }) => fetchOrder(companyId, orderId as string, signal),
    enabled: orderId !== null,
  })

  // The editor cannot render before the order's lines arrive, so the dialog opens with its own
  // loading state rather than flashing an empty form.
  if (orderId !== null && !orderQuery.data) {
    return (
      <TmsDrawer open title={t('form.loadingTitle')} subtitle={t('form.subtitle')} size="xl" onClose={onClose}>
        {orderQuery.isError ? (
          <div className="alert alert-danger py-2 small" role="alert">
            {describeApiError(orderQuery.error as ApiError)}
          </div>
        ) : (
          <LoadingState label={tc('loading.order')} />
        )}
      </TmsDrawer>
    )
  }

  return <OrderForm companyId={companyId} order={orderQuery.data ?? null} onClose={onClose} onSaved={onSaved} />
}

function OrderForm({
  companyId, order, onClose, onSaved,
}: {
  companyId: string
  order: OrderDetailView | null
  onClose: () => void
  onSaved: () => void
}) {
  const { t } = useTranslation('orders')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const isEdit = order !== null
  const isEditable = order === null || order.status === 'NOT_READY' || order.status === 'READY_FOR_PLANNING'
  const [formError, setFormError] = useState<string | null>(null)

  // Held here rather than derived from the form, because the id alone cannot render "LIM-01 -
  // Lima warehouse": the lookup hands back the whole record when the operator picks one, and
  // this is where that label lives until the drawer closes.
  const [origin, setOrigin] = useState<LookupOption | null>(
    assignedOption(order?.originId ?? null, order?.originCode ?? null, order?.originName ?? null),
  )
  const [destination, setDestination] = useState<LookupOption | null>(
    assignedOption(order?.destinationId ?? null, order?.destinationCode ?? null, order?.destinationName ?? null),
  )

  async function searchOrigins(term: string, signal: AbortSignal): Promise<LookupOption[]> {
    const page = await fetchOrigins({
      companyId, size: LOOKUP_PAGE_SIZE, active: true, sort: 'code,asc',
      search: term === '' ? undefined : term, signal,
    })
    return page.content.map(({ id, code, name }) => ({ id, code, name }))
  }

  async function searchDestinations(term: string, signal: AbortSignal): Promise<LookupOption[]> {
    const page = await fetchDestinations({
      companyId, size: LOOKUP_PAGE_SIZE, active: true, sort: 'code,asc',
      search: term === '' ? undefined : term, signal,
    })
    return page.content.map(({ id, code, name }) => ({ id, code, name }))
  }

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<OrderFormValues>({
    defaultValues: {
      externalSource: order?.externalSource ?? '',
      externalReference: order?.externalReference ?? '',
      originId: order?.originId ?? '',
      destinationId: order?.destinationId ?? '',
      customerName: order?.customerName ?? '',
      customerReference: order?.customerReference ?? '',
      serviceDate: order?.serviceDate ?? '',
      priority: order?.priority ?? 'NORMAL',
      requestedWindowStart: order?.requestedWindowStart?.slice(0, 5) ?? '',
      requestedWindowEnd: order?.requestedWindowEnd?.slice(0, 5) ?? '',
      declaredWeightKg: fromDeclared(order?.declaredWeightKg),
      declaredVolumeM3: fromDeclared(order?.declaredVolumeM3),
      declaredPallets: fromDeclared(order?.declaredPallets),
      lines: (order?.lines ?? []).map((line) => ({
        materialCode: line.materialCode,
        materialDescription: line.materialDescription,
        quantity: String(line.quantity),
        uom: line.uom,
        unitWeightKg: line.unitWeightKg === null ? '' : String(line.unitWeightKg),
        unitVolumeM3: line.unitVolumeM3 === null ? '' : String(line.unitVolumeM3),
        palletQuantity: line.palletQuantity === null ? '' : String(line.palletQuantity),
      })),
    },
  })
  const { fields, append, remove } = useFieldArray({ control, name: 'lines' })
  const watchedLines = useWatch({ control, name: 'lines' }) ?? []
  const watchedDeclared: DeclaredFormValues = {
    declaredWeightKg: useWatch({ control, name: 'declaredWeightKg' }) ?? '',
    declaredVolumeM3: useWatch({ control, name: 'declaredVolumeM3' }) ?? '',
    declaredPallets: useWatch({ control, name: 'declaredPallets' }) ?? '',
  }
  // useWatch already returns a fresh array every render, so a useMemo here would recompute on
  // every render anyway - it would only add a dependency-array footgun for no benefit.
  const totals = previewTotals(watchedLines, watchedDeclared)

  async function onSubmit(values: OrderFormValues) {
    setFormError(null)

    const request: OrderRequest = {
      externalSource: values.externalSource.trim() === '' ? null : values.externalSource.trim(),
      externalReference: values.externalReference.trim() === '' ? null : values.externalReference.trim(),
      originId: values.originId,
      destinationId: values.destinationId,
      customerName: values.customerName.trim() === '' ? null : values.customerName.trim(),
      customerReference: values.customerReference.trim() === '' ? null : values.customerReference.trim(),
      serviceDate: values.serviceDate,
      priority: values.priority,
      requestedWindowStart: values.requestedWindowStart.trim() === '' ? null : values.requestedWindowStart,
      requestedWindowEnd: values.requestedWindowEnd.trim() === '' ? null : values.requestedWindowEnd,
      declaredWeightKg: toDeclared(values.declaredWeightKg),
      declaredVolumeM3: toDeclared(values.declaredVolumeM3),
      declaredPallets: toDeclared(values.declaredPallets),
      version: order?.version,
      lines: values.lines.map((line) => ({
        materialCode: line.materialCode.trim(),
        materialDescription: line.materialDescription.trim(),
        quantity: Number(line.quantity),
        uom: line.uom.trim(),
        unitWeightKg: toNumberOrUndefined(line.unitWeightKg) ?? null,
        unitVolumeM3: toNumberOrUndefined(line.unitVolumeM3) ?? null,
        palletQuantity: toNumberOrUndefined(line.palletQuantity) ?? null,
      })),
    }

    try {
      if (isEdit) {
        await updateOrder(companyId, order.id, request)
      } else {
        await createOrder(companyId, request)
      }
      onSaved()
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  const statusLabel = order ? enumLabels.orderStatus(order.status) : ''

  return (
    <TmsDrawer
      open
      title={isEdit ? t('form.edit', { number: order.orderNumber }) : t('form.create')}
      subtitle={t('form.subtitle')}
      size="xl"
      onClose={onClose}
      dirty={isDirty}
      closeOnEscape={!isSubmitting}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={isSubmitting}>
            {tc('actions.cancel')}
          </button>
          {isEditable && (
            <button type="submit" form={FORM_ID} className="btn btn-primary" disabled={isSubmitting}>
              {isSubmitting ? tc('actions.saving') : tc('actions.save')}
            </button>
          )}
        </>
      }
    >
      <form id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {order && (
          <p className="mb-3">
            <StatusBadge label={statusLabel} tone="info" />
          </p>
        )}

        {!isEditable && (
          <p className="alert alert-secondary small py-2">
            {order?.cancelReason
              ? t('form.notEditableReason', { status: statusLabel, reason: order.cancelReason })
              : t('form.notEditable', { status: statusLabel })}
          </p>
        )}

        {formError && (
          <div className="alert alert-danger py-2 small" role="alert">
            {formError}
          </div>
        )}

        <fieldset disabled={!isEditable} className="border-0 p-0 m-0">
          <fieldset className="tms-fieldset">
            <legend className="tms-fieldset-legend">{tc('sections.identification')}</legend>
            <div className="row">
              <div className="col-12 col-sm-6">
                <FormField label={tc('fields.externalSource')} htmlFor="order-external-source">
                  <input id="order-external-source" className="form-control" {...register('externalSource')} />
                </FormField>
              </div>
              <div className="col-12 col-sm-6">
                <FormField label={tc('fields.externalReference')} htmlFor="order-external-reference">
                  <input id="order-external-reference" className="form-control" {...register('externalReference')} />
                </FormField>
              </div>
              <div className="col-12 col-sm-6">
                <FormField label={tc('fields.customerName')} htmlFor="order-customer-name">
                  <input id="order-customer-name" className="form-control" {...register('customerName')} />
                </FormField>
              </div>
              <div className="col-12 col-sm-6">
                <FormField label={tc('fields.customerReference')} htmlFor="order-customer-reference">
                  <input id="order-customer-reference" className="form-control" {...register('customerReference')} />
                </FormField>
              </div>
            </div>
          </fieldset>

          <fieldset className="tms-fieldset">
            <legend className="tms-fieldset-legend">{tc('sections.operation')}</legend>
            <div className="row">
              <div className="col-12 col-sm-6">
                <FormField label={tc('columns.origin')} htmlFor="order-origin" error={errors.originId?.message} required>
                  <Controller
                    control={control}
                    name="originId"
                    rules={{ required: tv('required') }}
                    render={({ field }) => (
                      <LookupField
                        id="order-origin"
                        value={field.value}
                        selected={origin}
                        queryKey={['origin-lookup', companyId]}
                        search={searchOrigins}
                        placeholder={t('form.searchOrigin')}
                        disabled={!isEditable}
                        invalid={errors.originId !== undefined}
                        onChange={(option) => {
                          setOrigin(option)
                          field.onChange(option?.id ?? '')
                        }}
                      />
                    )}
                  />
                </FormField>
              </div>
              <div className="col-12 col-sm-6">
                <FormField
                  label={tc('fields.destination')}
                  htmlFor="order-destination"
                  error={errors.destinationId?.message}
                  required
                >
                  <Controller
                    control={control}
                    name="destinationId"
                    rules={{ required: tv('required') }}
                    render={({ field }) => (
                      <LookupField
                        id="order-destination"
                        value={field.value}
                        selected={destination}
                        queryKey={['destination-lookup', companyId]}
                        search={searchDestinations}
                        placeholder={t('form.searchDestination')}
                        disabled={!isEditable}
                        invalid={errors.destinationId !== undefined}
                        onChange={(option) => {
                          setDestination(option)
                          field.onChange(option?.id ?? '')
                        }}
                      />
                    )}
                  />
                </FormField>
              </div>
              <div className="col-6 col-sm-2">
                <FormField
                  label={tc('fields.serviceDate')}
                  htmlFor="order-service-date"
                  error={errors.serviceDate?.message}
                  required
                >
                  <input
                    id="order-service-date"
                    type="date"
                    className={`form-control${errors.serviceDate ? ' is-invalid' : ''}`}
                    {...register('serviceDate', { required: tv('required') })}
                  />
                </FormField>
              </div>
              <div className="col-6 col-sm-2">
                <FormField label={tc('fields.priority')} htmlFor="order-priority" required>
                  <select id="order-priority" className="form-select" {...register('priority')}>
                    {ORDER_PRIORITIES.map((priority) => (
                      <option key={priority} value={priority}>
                        {enumLabels.orderPriority(priority)}
                      </option>
                    ))}
                  </select>
                </FormField>
              </div>
              <div className="col-6 col-sm-1">
                <FormField
                  label={tc('fields.windowFrom')}
                  htmlFor="order-window-start"
                  error={errors.requestedWindowStart?.message}
                >
                  <input
                    id="order-window-start"
                    type="time"
                    className={`form-control${errors.requestedWindowStart ? ' is-invalid' : ''}`}
                    {...register('requestedWindowStart')}
                  />
                </FormField>
              </div>
              <div className="col-6 col-sm-1">
                <FormField
                  label={tc('fields.windowTo')}
                  htmlFor="order-window-end"
                  error={errors.requestedWindowEnd?.message}
                >
                  <input
                    id="order-window-end"
                    type="time"
                    className={`form-control${errors.requestedWindowEnd ? ' is-invalid' : ''}`}
                    {...register('requestedWindowEnd')}
                  />
                </FormField>
              </div>
            </div>
          </fieldset>

          <fieldset className="tms-fieldset">
            <legend className="tms-fieldset-legend">{t('form.declaredTotals')}</legend>
            <p className="text-body-secondary small mb-3">{t('form.declaredHelp')}</p>
            <div className="row">
              <div className="col-6 col-sm-4">
                <FormField
                  label={t('form.declaredWeight')}
                  htmlFor="order-declared-weight"
                  error={errors.declaredWeightKg?.message}
                >
                  <input
                    id="order-declared-weight"
                    type="number"
                    min={0}
                    step="0.001"
                    className={`form-control${errors.declaredWeightKg ? ' is-invalid' : ''}`}
                    {...register('declaredWeightKg', { min: { value: 0, message: tv('nonNegative') } })}
                  />
                </FormField>
              </div>
              <div className="col-6 col-sm-4">
                <FormField
                  label={t('form.declaredVolume')}
                  htmlFor="order-declared-volume"
                  error={errors.declaredVolumeM3?.message}
                >
                  <input
                    id="order-declared-volume"
                    type="number"
                    min={0}
                    step="0.0001"
                    className={`form-control${errors.declaredVolumeM3 ? ' is-invalid' : ''}`}
                    {...register('declaredVolumeM3', { min: { value: 0, message: tv('nonNegative') } })}
                  />
                </FormField>
              </div>
              <div className="col-6 col-sm-4">
                <FormField
                  label={t('form.declaredPallets')}
                  htmlFor="order-declared-pallets"
                  error={errors.declaredPallets?.message}
                >
                  <input
                    id="order-declared-pallets"
                    type="number"
                    min={0}
                    step="0.01"
                    className={`form-control${errors.declaredPallets ? ' is-invalid' : ''}`}
                    {...register('declaredPallets', { min: { value: 0, message: tv('nonNegative') } })}
                  />
                </FormField>
              </div>
            </div>
            <p className="text-body-secondary small mb-0">
              {watchedLines.length > 0 ? t('form.declaredWithLines') : t('form.declaredWithoutLines')}
            </p>
          </fieldset>

          <fieldset className="tms-fieldset mb-0">
            <legend className="tms-fieldset-legend">{t('form.lines')}</legend>

            {fields.length === 0 && <p className="text-body-secondary small">{t('form.noLines')}</p>}
            {fields.length > 0 && (
              <div className="tms-table-scroll mb-2">
                <table className="table table-sm align-middle">
                  <thead>
                    <tr>
                      <th scope="col">{t('form.materialCode')}</th>
                      <th scope="col">{tc('columns.description')}</th>
                      <th scope="col">{t('form.quantity')}</th>
                      <th scope="col">{t('form.uom')}</th>
                      <th scope="col">{t('form.unitWeight')}</th>
                      <th scope="col">{t('form.unitVolume')}</th>
                      <th scope="col">{t('form.pallets')}</th>
                      <th scope="col">
                        <span className="visually-hidden">{tc('columns.actions')}</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {fields.map((field, index) => {
                      const position = index + 1
                      return (
                        <tr key={field.id}>
                          <td style={{ minWidth: '9rem' }}>
                            <input
                              className="form-control form-control-sm"
                              aria-label={t('form.ariaMaterialCode', { position })}
                              {...register(`lines.${index}.materialCode`, { required: tv('required') })}
                            />
                          </td>
                          <td style={{ minWidth: '12rem' }}>
                            <input
                              className="form-control form-control-sm"
                              aria-label={t('form.ariaDescription', { position })}
                              {...register(`lines.${index}.materialDescription`, { required: tv('required') })}
                            />
                          </td>
                          <td style={{ minWidth: '6rem' }}>
                            <input
                              type="number"
                              min={0}
                              step="0.001"
                              className="form-control form-control-sm"
                              aria-label={t('form.ariaQuantity', { position })}
                              {...register(`lines.${index}.quantity`, {
                                required: tv('required'),
                                min: { value: 0.001, message: tv('positiveNumber') },
                              })}
                            />
                          </td>
                          <td style={{ minWidth: '6rem' }}>
                            <input
                              className="form-control form-control-sm"
                              aria-label={t('form.ariaUom', { position })}
                              {...register(`lines.${index}.uom`, { required: tv('required') })}
                            />
                          </td>
                          <td style={{ minWidth: '7rem' }}>
                            <input
                              type="number"
                              min={0}
                              step="0.001"
                              className="form-control form-control-sm"
                              aria-label={t('form.ariaUnitWeight', { position })}
                              {...register(`lines.${index}.unitWeightKg`, {
                                min: { value: 0.001, message: tv('positiveNumber') },
                              })}
                            />
                          </td>
                          <td style={{ minWidth: '7rem' }}>
                            <input
                              type="number"
                              min={0}
                              step="0.0001"
                              className="form-control form-control-sm"
                              aria-label={t('form.ariaUnitVolume', { position })}
                              {...register(`lines.${index}.unitVolumeM3`, {
                                min: { value: 0.0001, message: tv('positiveNumber') },
                              })}
                            />
                          </td>
                          <td style={{ minWidth: '6rem' }}>
                            <input
                              type="number"
                              min={0}
                              step="0.01"
                              className="form-control form-control-sm"
                              aria-label={t('form.ariaPallets', { position })}
                              {...register(`lines.${index}.palletQuantity`, {
                                min: { value: 0, message: tv('nonNegative') },
                              })}
                            />
                          </td>
                          <td>
                            <button
                              type="button"
                              className="btn btn-sm btn-outline-danger"
                              aria-label={t('form.removeLine', { position })}
                              onClick={() => remove(index)}
                            >
                              {t('form.remove')}
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}

            <button type="button" className="btn btn-outline-primary btn-sm" onClick={() => append(BLANK_LINE)}>
              {t('form.addLine')}
            </button>

            <div className="alert alert-light border small mt-3 mb-0">
              <p className="mb-1">
                {t('form.totalsLabel')}: <strong>{format.weight(totals.weight)}</strong> ·{' '}
                <strong>{format.volume(totals.volume)}</strong> ·{' '}
                <strong>
                  {format.decimal(totals.pallets)} {t('form.palletsUnit')}
                </strong>
              </p>
              <p className="text-body-secondary mb-0">
                {totals.calculated ? t('form.totalsSourceCalculated') : t('form.totalsSourceDeclared')}
              </p>
            </div>
          </fieldset>
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
