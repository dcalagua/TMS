import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useFieldArray, useForm, useWatch } from 'react-hook-form'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchDestinations } from '../../shared/api/destinationsApi'
import { fetchOrigins } from '../../shared/api/originsApi'
import {
  ORDER_PRIORITIES,
  ORDER_PRIORITY_LABELS,
  ORDER_STATUS_LABELS,
  createOrder,
  fetchOrder,
  updateOrder,
  type OrderDetailView,
  type OrderPriority,
  type OrderRequest,
} from '../../shared/api/ordersApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { FormField } from '../../shared/ui/components/FormField'
import { LoadingState } from '../../shared/ui/components/LoadingState'
import { StatusBadge } from '../../shared/ui/components/StatusBadge'

interface SelectOption {
  id: string
  code: string
  name: string
}

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
  lines: OrderLineFormValues[]
}

interface OrderFormModalProps {
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
])

const BLANK_LINE: OrderLineFormValues = {
  materialCode: '', materialDescription: '', quantity: '1', uom: 'EA', unitWeightKg: '', unitVolumeM3: '', palletQuantity: '',
}

/** Prepends the currently assigned option if it fell out of the active-only list fetched for the
 * dropdown - the same "deactivating does not silently break the editor" invariant `RouteFormModal`
 * uses for its origin/zone/frequency selects. */
function withCurrentValue(options: SelectOption[], id: string, code: string | null, name: string | null) {
  if (id === '' || options.some((option) => option.id === id)) {
    return options
  }
  return [{ id, code: code ?? id, name: name ?? code ?? id }, ...options]
}

function toNumberOrUndefined(value: string): number | undefined {
  return value.trim() === '' ? undefined : Number(value)
}

/** Client-side preview only, mirroring `TransportOrderLine.applyInput`'s formula - never sent to
 * the backend and never trusted as the authoritative total. The server always recomputes and
 * returns the real totals (`OrderDetailView.totalWeightKg`/...) after save. */
function previewTotals(lines: OrderLineFormValues[]) {
  let weight = 0;
  let volume = 0;
  let pallets = 0;
  for (const line of lines) {
    const quantity = Number(line.quantity) || 0
    const unitWeight = toNumberOrUndefined(line.unitWeightKg)
    const unitVolume = toNumberOrUndefined(line.unitVolumeM3)
    const palletQuantity = toNumberOrUndefined(line.palletQuantity)
    if (unitWeight !== undefined) weight += quantity * unitWeight
    if (unitVolume !== undefined) volume += quantity * unitVolume
    if (palletQuantity !== undefined) pallets += palletQuantity
  }
  return { weight, volume, pallets }
}

export function OrderFormModal({ companyId, orderId, onClose, onSaved }: OrderFormModalProps) {
  const orderQuery = useQuery({
    queryKey: ['order', companyId, orderId],
    queryFn: ({ signal }) => fetchOrder(companyId, orderId as string, signal),
    enabled: orderId !== null,
  })

  if (orderId !== null && !orderQuery.data) {
    return (
      <div className="modal d-block" tabIndex={-1} role="dialog" aria-modal="true">
        <div className="modal-dialog modal-xl" role="document">
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">Order</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              {orderQuery.isError ? (
                <div className="alert alert-danger py-2 small" role="alert">
                  {describeApiError(orderQuery.error as ApiError)}
                </div>
              ) : (
                <LoadingState label="Loading order..." />
              )}
            </div>
          </div>
        </div>
        <div className="modal-backdrop show" />
      </div>
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
  const isEdit = order !== null
  const isEditable = order === null || order.status === 'NOT_READY' || order.status === 'READY_FOR_PLANNING'
  const [formError, setFormError] = useState<string | null>(null)

  const originsQuery = useQuery({
    queryKey: ['origins-for-order-form', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const destinationsQuery = useQuery({
    queryKey: ['destinations-for-order-form', companyId],
    queryFn: ({ signal }) => fetchDestinations({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })

  const originOptions = withCurrentValue(
    originsQuery.data?.content ?? [], order?.originId ?? '', order?.originCode ?? null, order?.originName ?? null,
  )
  const destinationOptions = withCurrentValue(
    destinationsQuery.data?.content ?? [], order?.destinationId ?? '', order?.destinationCode ?? null,
    order?.destinationName ?? null,
  )

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
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
  // useWatch already returns a fresh array every render, so a useMemo here would recompute on
  // every render anyway - it would only add a dependency-array footgun for no benefit.
  const totals = previewTotals(watchedLines)

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
      const apiError = error as ApiError
      if (apiError.fieldErrors.length > 0) {
        const unmatched: string[] = []
        for (const fieldError of apiError.fieldErrors) {
          if (KNOWN_FIELDS.has(fieldError.field as keyof OrderFormValues)) {
            setError(fieldError.field as keyof OrderFormValues, { message: fieldError.message })
          } else {
            unmatched.push(fieldError.message)
          }
        }
        setFormError(unmatched.length > 0 ? unmatched.join(' ') : 'Please correct the highlighted fields.')
      } else {
        setFormError(describeApiError(apiError))
      }
    }
  }

  return (
    <div
      className="modal d-block"
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      onKeyDown={(event) => {
        if (event.key === 'Escape') onClose()
      }}
    >
      <div className="modal-dialog modal-xl" role="document">
        <div className="modal-content">
          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <div className="modal-header">
              <h5 className="modal-title">
                {isEdit ? `Order ${order.orderNumber}` : 'New order'}
                {order && (
                  <StatusBadge label={ORDER_STATUS_LABELS[order.status]} tone="info" />
                )}
              </h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              {!isEditable && (
                <div className="alert alert-secondary small py-2" role="note">
                  This order is {ORDER_STATUS_LABELS[order?.status ?? 'CANCELLED'].toLowerCase()} and can no longer be
                  edited{order?.cancelReason ? ` (reason: ${order.cancelReason})` : ''}.
                </div>
              )}
              {formError && (
                <div className="alert alert-danger py-2 small" role="alert">
                  {formError}
                </div>
              )}
              <fieldset disabled={!isEditable}>
                <div className="row">
                  <div className="col-md-3">
                    <FormField label="External source" htmlFor="order-external-source">
                      <input id="order-external-source" className="form-control" {...register('externalSource')} />
                    </FormField>
                  </div>
                  <div className="col-md-3">
                    <FormField label="External reference" htmlFor="order-external-reference">
                      <input id="order-external-reference" className="form-control" {...register('externalReference')} />
                    </FormField>
                  </div>
                  <div className="col-md-3">
                    <FormField label="Customer name" htmlFor="order-customer-name">
                      <input id="order-customer-name" className="form-control" {...register('customerName')} />
                    </FormField>
                  </div>
                  <div className="col-md-3">
                    <FormField label="Customer reference" htmlFor="order-customer-reference">
                      <input id="order-customer-reference" className="form-control" {...register('customerReference')} />
                    </FormField>
                  </div>
                </div>
                <div className="row">
                  <div className="col-md-3">
                    <FormField label="Origin" htmlFor="order-origin" error={errors.originId?.message} required>
                      <select
                        id="order-origin"
                        className={`form-select${errors.originId ? ' is-invalid' : ''}`}
                        {...register('originId', { required: 'Origin is required' })}
                      >
                        <option value="">Select an origin</option>
                        {originOptions.map((origin) => (
                          <option key={origin.id} value={origin.id}>
                            {origin.name}
                          </option>
                        ))}
                      </select>
                    </FormField>
                  </div>
                  <div className="col-md-3">
                    <FormField label="Destination" htmlFor="order-destination" error={errors.destinationId?.message} required>
                      <select
                        id="order-destination"
                        className={`form-select${errors.destinationId ? ' is-invalid' : ''}`}
                        {...register('destinationId', { required: 'Destination is required' })}
                      >
                        <option value="">Select a destination</option>
                        {destinationOptions.map((destination) => (
                          <option key={destination.id} value={destination.id}>
                            {destination.name}
                          </option>
                        ))}
                      </select>
                    </FormField>
                  </div>
                  <div className="col-md-2">
                    <FormField label="Service date" htmlFor="order-service-date" error={errors.serviceDate?.message} required>
                      <input
                        id="order-service-date"
                        type="date"
                        className={`form-control${errors.serviceDate ? ' is-invalid' : ''}`}
                        {...register('serviceDate', { required: 'Service date is required' })}
                      />
                    </FormField>
                  </div>
                  <div className="col-md-2">
                    <FormField label="Priority" htmlFor="order-priority" required>
                      <select id="order-priority" className="form-select" {...register('priority')}>
                        {ORDER_PRIORITIES.map((priority) => (
                          <option key={priority} value={priority}>
                            {ORDER_PRIORITY_LABELS[priority]}
                          </option>
                        ))}
                      </select>
                    </FormField>
                  </div>
                  <div className="col-md-1">
                    <FormField label="Window from" htmlFor="order-window-start" error={errors.requestedWindowStart?.message}>
                      <input
                        id="order-window-start"
                        type="time"
                        className={`form-control${errors.requestedWindowStart ? ' is-invalid' : ''}`}
                        {...register('requestedWindowStart')}
                      />
                    </FormField>
                  </div>
                  <div className="col-md-1">
                    <FormField label="Window to" htmlFor="order-window-end" error={errors.requestedWindowEnd?.message}>
                      <input
                        id="order-window-end"
                        type="time"
                        className={`form-control${errors.requestedWindowEnd ? ' is-invalid' : ''}`}
                        {...register('requestedWindowEnd')}
                      />
                    </FormField>
                  </div>
                </div>

                <label className="form-label mt-2">Lines</label>
                {fields.length === 0 && <p className="text-body-secondary small">No lines added yet.</p>}
                {fields.length > 0 && (
                  <div className="table-responsive mb-2">
                    <table className="table table-sm align-middle">
                      <thead>
                        <tr>
                          <th scope="col">Material code</th>
                          <th scope="col">Description</th>
                          <th scope="col">Qty</th>
                          <th scope="col">UOM</th>
                          <th scope="col">Unit weight (kg)</th>
                          <th scope="col">Unit volume (m³)</th>
                          <th scope="col">Pallets</th>
                          <th scope="col" />
                        </tr>
                      </thead>
                      <tbody>
                        {fields.map((field, index) => (
                          <tr key={field.id}>
                            <td style={{ minWidth: '9rem' }}>
                              <input
                                className="form-control form-control-sm"
                                aria-label={`Line ${index + 1} material code`}
                                {...register(`lines.${index}.materialCode`, { required: 'Required' })}
                              />
                            </td>
                            <td style={{ minWidth: '12rem' }}>
                              <input
                                className="form-control form-control-sm"
                                aria-label={`Line ${index + 1} description`}
                                {...register(`lines.${index}.materialDescription`, { required: 'Required' })}
                              />
                            </td>
                            <td style={{ minWidth: '6rem' }}>
                              <input
                                type="number"
                                min={0}
                                step="0.001"
                                className="form-control form-control-sm"
                                aria-label={`Line ${index + 1} quantity`}
                                {...register(`lines.${index}.quantity`, {
                                  required: 'Required', min: { value: 0.001, message: 'Must be greater than zero' },
                                })}
                              />
                            </td>
                            <td style={{ minWidth: '6rem' }}>
                              <input
                                className="form-control form-control-sm"
                                aria-label={`Line ${index + 1} unit of measure`}
                                {...register(`lines.${index}.uom`, { required: 'Required' })}
                              />
                            </td>
                            <td style={{ minWidth: '7rem' }}>
                              <input
                                type="number"
                                min={0}
                                step="0.001"
                                className="form-control form-control-sm"
                                aria-label={`Line ${index + 1} unit weight`}
                                {...register(`lines.${index}.unitWeightKg`, {
                                  min: { value: 0.001, message: 'Must be greater than zero' },
                                })}
                              />
                            </td>
                            <td style={{ minWidth: '7rem' }}>
                              <input
                                type="number"
                                min={0}
                                step="0.0001"
                                className="form-control form-control-sm"
                                aria-label={`Line ${index + 1} unit volume`}
                                {...register(`lines.${index}.unitVolumeM3`, {
                                  min: { value: 0.0001, message: 'Must be greater than zero' },
                                })}
                              />
                            </td>
                            <td style={{ minWidth: '6rem' }}>
                              <input
                                type="number"
                                min={0}
                                step="0.01"
                                className="form-control form-control-sm"
                                aria-label={`Line ${index + 1} pallet quantity`}
                                {...register(`lines.${index}.palletQuantity`, {
                                  min: { value: 0, message: 'Must be zero or greater' },
                                })}
                              />
                            </td>
                            <td>
                              <button
                                type="button"
                                className="btn btn-sm btn-outline-danger"
                                aria-label={`Remove line ${index + 1}`}
                                onClick={() => remove(index)}
                              >
                                Remove
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <button type="button" className="btn btn-outline-primary btn-sm" onClick={() => append(BLANK_LINE)}>
                  Add line
                </button>

                <div className="alert alert-light border small mt-3 mb-0" role="note">
                  Estimated totals (server-computed on save): <strong>{totals.weight.toFixed(3)} kg</strong> ·{' '}
                  <strong>{totals.volume.toFixed(4)} m³</strong> · <strong>{totals.pallets.toFixed(2)} pallets</strong>
                </div>
              </fieldset>
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-outline-secondary" onClick={onClose}>
                Cancel
              </button>
              {isEditable && (
                <button type="submit" className="btn btn-primary" disabled={isSubmitting}>
                  {isSubmitting ? 'Saving...' : 'Save'}
                </button>
              )}
            </div>
          </form>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </div>
  )
}
