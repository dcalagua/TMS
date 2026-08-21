import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useForm, type Validate } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { fetchCarriers } from '../../shared/api/carriersApi'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchOrigins } from '../../shared/api/originsApi'
import {
  createRateCard,
  RATE_CARD_SCOPES,
  updateRateCard,
  type RateCardRequest,
  type RateCardScope,
  type RateCardView,
} from '../../shared/api/ratesApi'
import { fetchRoutes } from '../../shared/api/routesApi'
import { fetchVehicleTypes } from '../../shared/api/vehicleTypesApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { FormField } from '../../shared/ui/components/FormField'
import { Select } from '../../shared/ui/components/Select'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'rate-card-form'

/** Matches the backend's constraints; kept next to the fields they validate. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/
const CURRENCY_PATTERN = /^[A-Za-z]{3}$/

interface RateCardFormValues {
  code: string
  name: string
  carrierId: string
  scope: RateCardScope
  originId: string
  routeId: string
  vehicleTypeId: string
  currency: string
  validFrom: string
  validTo: string
  baseAmount: string
  amountPerKm: string
  amountPerKg: string
  amountPerM3: string
  amountPerPallet: string
  minimumAmount: string
}

interface RateCardFormDrawerProps {
  companyId: string
  /** `null` creates a new card; otherwise the form edits this one. */
  card: RateCardView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof RateCardFormValues>([
  'code', 'name', 'carrierId', 'scope', 'originId', 'routeId', 'vehicleTypeId', 'currency', 'validFrom', 'validTo',
  'baseAmount', 'amountPerKm', 'amountPerKg', 'amountPerM3', 'amountPerPallet', 'minimumAmount',
])

/**
 * Create and edit share one form.
 *
 * Two things it enforces before the request is sent, both of which the backend refuses anyway -
 * they are here so the refusal arrives while the operator is still looking at the field:
 * the scope's target (an origin, a route, or neither), and that the card charges for something.
 *
 * The carrier is locked once a card exists. That is not a UI convenience: re-pointing an
 * agreement at another carrier would restate every estimate that has already cited it, so the
 * backend refuses it too and the answer is a new card.
 */
export function RateCardFormDrawer({ companyId, card, onClose, onSaved }: RateCardFormDrawerProps) {
  const { t } = useTranslation('rates')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const enumLabels = useEnumLabels()
  const isEdit = card !== null
  const [formError, setFormError] = useState<string | null>(null)

  const carriersQuery = useQuery({
    queryKey: ['carriers-for-rate-form', companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const carriers = carriersQuery.data?.content ?? []

  const originsQuery = useQuery({
    queryKey: ['origins-for-rate-form', companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const origins = originsQuery.data?.content ?? []

  const routesQuery = useQuery({
    queryKey: ['routes-for-rate-form', companyId],
    queryFn: ({ signal }) => fetchRoutes({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const routes = routesQuery.data?.content ?? []

  const vehicleTypesQuery = useQuery({
    queryKey: ['vehicle-types-for-rate-form', companyId],
    queryFn: ({ signal }) => fetchVehicleTypes({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const vehicleTypes = vehicleTypesQuery.data?.content ?? []

  const {
    register,
    control,
    watch,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<RateCardFormValues>({
    defaultValues: {
      code: card?.code ?? '',
      name: card?.name ?? '',
      carrierId: card?.carrierId ?? '',
      scope: card?.scope ?? 'CARRIER',
      originId: (card?.scope === 'ORIGIN' ? card.scopeTargetId : '') ?? '',
      routeId: (card?.scope === 'ROUTE' ? card.scopeTargetId : '') ?? '',
      vehicleTypeId: card?.vehicleTypeId ?? '',
      currency: card?.currency ?? '',
      validFrom: card?.validFrom ?? '',
      validTo: card?.validTo ?? '',
      baseAmount: card?.baseAmount?.toString() ?? '',
      amountPerKm: card?.amountPerKm?.toString() ?? '',
      amountPerKg: card?.amountPerKg?.toString() ?? '',
      amountPerM3: card?.amountPerM3?.toString() ?? '',
      amountPerPallet: card?.amountPerPallet?.toString() ?? '',
      minimumAmount: card?.minimumAmount?.toString() ?? '',
    },
  })

  const scope = watch('scope')

  const validateAmount: Validate<string, RateCardFormValues> = (value) => {
    if (value.trim() === '') return true
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return tv('number')
    return parsed >= 0 || tv('nonNegative')
  }

  function toNumberOrNull(value: string): number | null {
    return value.trim() === '' ? null : Number(value)
  }

  async function onSubmit(values: RateCardFormValues) {
    setFormError(null)
    if (values.scope === 'ORIGIN' && !values.originId) {
      setError('originId', { message: tv('required') })
      return
    }
    if (values.scope === 'ROUTE' && !values.routeId) {
      setError('routeId', { message: tv('required') })
      return
    }

    const request: RateCardRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      carrierId: values.carrierId,
      scope: values.scope,
      originId: values.scope === 'ORIGIN' ? values.originId : null,
      routeId: values.scope === 'ROUTE' ? values.routeId : null,
      vehicleTypeId: values.vehicleTypeId || null,
      currency: values.currency.trim().toUpperCase(),
      validFrom: values.validFrom,
      validTo: values.validTo || null,
      baseAmount: toNumberOrNull(values.baseAmount),
      amountPerKm: toNumberOrNull(values.amountPerKm),
      amountPerKg: toNumberOrNull(values.amountPerKg),
      amountPerM3: toNumberOrNull(values.amountPerM3),
      amountPerPallet: toNumberOrNull(values.amountPerPallet),
      minimumAmount: toNumberOrNull(values.minimumAmount),
    }

    const charges = [
      request.baseAmount,
      request.amountPerKm,
      request.amountPerKg,
      request.amountPerM3,
      request.amountPerPallet,
    ]
    if (charges.every((amount) => amount === null)) {
      setFormError(t('form.needsAComponent'))
      return
    }

    try {
      if (isEdit) {
        await updateRateCard(companyId, card.id, request)
      } else {
        await createRateCard(companyId, request)
      }
      onSaved()
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('form.edit') : t('form.create')}
      subtitle={t('form.subtitle')}
      size="lg"
      onClose={onClose}
      dirty={isDirty}
      closeOnEscape={!isSubmitting}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={isSubmitting}>
            {tc('actions.cancel')}
          </button>
          <button type="submit" form={FORM_ID} className="btn btn-primary" disabled={isSubmitting}>
            {isSubmitting ? tc('actions.saving') : tc('actions.save')}
          </button>
        </>
      }
    >
      <form id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && (
          <div className="alert alert-danger py-2 small" role="alert">
            {formError}
          </div>
        )}

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{tc('sections.identification')}</legend>
          <div className="row">
            <div className="col-12 col-sm-4">
              <FormField label={tc('columns.code')} htmlFor="rate-code" error={errors.code?.message} required>
                <input
                  id="rate-code"
                  className={`form-control${errors.code ? ' is-invalid' : ''}`}
                  {...register('code', {
                    required: tv('required'),
                    maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                    pattern: { value: CODE_PATTERN, message: tv('codePattern') },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-8">
              <FormField label={tc('columns.name')} htmlFor="rate-name" error={errors.name?.message} required>
                <input
                  id="rate-name"
                  className={`form-control${errors.name ? ' is-invalid' : ''}`}
                  {...register('name', {
                    required: tv('required'),
                    maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                  })}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{t('form.sections.scope')}</legend>
          <div className="row">
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('columns.carrier')}
                htmlFor="rate-carrier"
                error={errors.carrierId?.message}
                required
                help={isEdit ? t('form.carrierLocked') : undefined}
              >
                <Controller
                  control={control}
                  name="carrierId"
                  rules={{ required: tv('required') }}
                  render={({ field }) => (
                    <Select
                      id="rate-carrier"
                      value={field.value}
                      onChange={field.onChange}
                      disabled={isEdit}
                      invalid={Boolean(errors.carrierId)}
                      options={[
                        { value: '', label: t('form.selectCarrier') },
                        ...(card?.carrierId && !carriers.some((carrier) => carrier.id === card.carrierId)
                          ? [{ value: card.carrierId, label: card.carrierCode ?? card.carrierId }]
                          : []),
                        ...carriers.map((carrier) => ({
                          value: carrier.id,
                          label: `${carrier.code} — ${carrier.businessName}`,
                        })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-6">
              <FormField label={t('columns.scope')} htmlFor="rate-scope" error={errors.scope?.message} required>
                <Controller
                  control={control}
                  name="scope"
                  rules={{ required: true }}
                  render={({ field }) => (
                    <Select
                      id="rate-scope"
                      value={field.value}
                      onChange={(value) => field.onChange(value as RateCardScope)}
                      options={RATE_CARD_SCOPES.map((value) => ({
                        value,
                        label: enumLabels.rateCardScope(value),
                      }))}
                    />
                  )}
                />
              </FormField>
            </div>
            {scope === 'ORIGIN' && (
              <div className="col-12 col-sm-6">
                <FormField
                  label={tc('columns.origin')}
                  htmlFor="rate-origin"
                  error={errors.originId?.message}
                  required
                >
                  <Controller
                    control={control}
                    name="originId"
                    render={({ field }) => (
                      <Select
                        id="rate-origin"
                        value={field.value}
                        onChange={field.onChange}
                        invalid={Boolean(errors.originId)}
                        options={[
                          { value: '', label: t('form.selectOrigin') },
                          ...origins.map((origin) => ({
                            value: origin.id,
                            label: `${origin.code} — ${origin.name}`,
                          })),
                        ]}
                      />
                    )}
                  />
                </FormField>
              </div>
            )}
            {scope === 'ROUTE' && (
              <div className="col-12 col-sm-6">
                <FormField label={t('form.route')} htmlFor="rate-route" error={errors.routeId?.message} required>
                  <Controller
                    control={control}
                    name="routeId"
                    render={({ field }) => (
                      <Select
                        id="rate-route"
                        value={field.value}
                        onChange={field.onChange}
                        invalid={Boolean(errors.routeId)}
                        options={[
                          { value: '', label: t('form.selectRoute') },
                          ...routes.map((route) => ({ value: route.id, label: `${route.code} — ${route.name}` })),
                        ]}
                      />
                    )}
                  />
                </FormField>
              </div>
            )}
            <div className="col-12 col-sm-6">
              <FormField
                label={tc('fields.vehicleType')}
                htmlFor="rate-vehicle-type"
                error={errors.vehicleTypeId?.message}
                help={t('form.vehicleTypeHelp')}
              >
                <Controller
                  control={control}
                  name="vehicleTypeId"
                  render={({ field }) => (
                    <Select
                      id="rate-vehicle-type"
                      value={field.value}
                      onChange={field.onChange}
                      options={[
                        { value: '', label: t('anyVehicleType') },
                        ...vehicleTypes.map((type) => ({ value: type.id, label: `${type.code} — ${type.name}` })),
                      ]}
                    />
                  )}
                />
              </FormField>
            </div>
          </div>
          <p className="text-body-secondary small mb-0">{t(`scopeHint.${scope}`)}</p>
        </fieldset>

        <fieldset className="tms-fieldset">
          <legend className="tms-fieldset-legend">{t('form.sections.validity')}</legend>
          <div className="row">
            <div className="col-12 col-sm-4">
              <FormField
                label={t('form.currency')}
                htmlFor="rate-currency"
                error={errors.currency?.message}
                required
                help={t('form.currencyHelp')}
              >
                <input
                  id="rate-currency"
                  maxLength={3}
                  placeholder="PEN"
                  className={`form-control text-uppercase${errors.currency ? ' is-invalid' : ''}`}
                  {...register('currency', {
                    required: tv('required'),
                    pattern: { value: CURRENCY_PATTERN, message: t('form.currencyHelp') },
                  })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('fields.effectiveFrom')}
                htmlFor="rate-valid-from"
                error={errors.validFrom?.message}
                required
              >
                <input
                  id="rate-valid-from"
                  type="date"
                  className={`form-control${errors.validFrom ? ' is-invalid' : ''}`}
                  {...register('validFrom', { required: tv('required') })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={tc('fields.effectiveTo')}
                htmlFor="rate-valid-to"
                error={errors.validTo?.message}
                help={t('form.validToHelp')}
              >
                <input
                  id="rate-valid-to"
                  type="date"
                  className={`form-control${errors.validTo ? ' is-invalid' : ''}`}
                  {...register('validTo')}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{t('form.sections.components')}</legend>
          <div className="row">
            <div className="col-12 col-sm-4">
              <FormField label={t('form.baseAmount')} htmlFor="rate-base" error={errors.baseAmount?.message}>
                <input
                  id="rate-base"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.baseAmount ? ' is-invalid' : ''}`}
                  {...register('baseAmount', { validate: validateAmount })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField label={t('form.amountPerKm')} htmlFor="rate-per-km" error={errors.amountPerKm?.message}>
                <input
                  id="rate-per-km"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.amountPerKm ? ' is-invalid' : ''}`}
                  {...register('amountPerKm', { validate: validateAmount })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField label={t('form.amountPerKg')} htmlFor="rate-per-kg" error={errors.amountPerKg?.message}>
                <input
                  id="rate-per-kg"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.amountPerKg ? ' is-invalid' : ''}`}
                  {...register('amountPerKg', { validate: validateAmount })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField label={t('form.amountPerM3')} htmlFor="rate-per-m3" error={errors.amountPerM3?.message}>
                <input
                  id="rate-per-m3"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.amountPerM3 ? ' is-invalid' : ''}`}
                  {...register('amountPerM3', { validate: validateAmount })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={t('form.amountPerPallet')}
                htmlFor="rate-per-pallet"
                error={errors.amountPerPallet?.message}
              >
                <input
                  id="rate-per-pallet"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.amountPerPallet ? ' is-invalid' : ''}`}
                  {...register('amountPerPallet', { validate: validateAmount })}
                />
              </FormField>
            </div>
            <div className="col-12 col-sm-4">
              <FormField
                label={t('form.minimumAmount')}
                htmlFor="rate-minimum"
                error={errors.minimumAmount?.message}
                help={t('form.minimumHelp')}
              >
                <input
                  id="rate-minimum"
                  type="text"
                  inputMode="decimal"
                  className={`form-control${errors.minimumAmount ? ' is-invalid' : ''}`}
                  {...register('minimumAmount', { validate: validateAmount })}
                />
              </FormField>
            </div>
          </div>
          <p className="text-body-secondary small mb-0">{t('form.componentsHelp')}</p>
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
