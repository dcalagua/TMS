import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import { createZone, updateZone, type ZoneRequest, type ZoneView } from '../../shared/api/zonesApi'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'zone-form'

/** Matches the backend's `code` constraint; kept next to the field it validates. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/

interface ZoneFormValues {
  code: string
  name: string
  description: string
}

interface ZoneFormDrawerProps {
  companyId: string
  /** `null` creates a new zone; otherwise the form edits this one. */
  zone: ZoneView | null
  onClose: () => void
  onSaved: () => void
}

const KNOWN_FIELDS = new Set<keyof ZoneFormValues>(['code', 'name', 'description'])

/** Create and edit share one form; see `OriginFormDrawer` for the same pattern with more fields. */
export function ZoneFormDrawer({ companyId, zone, onClose, onSaved }: ZoneFormDrawerProps) {
  const { t } = useTranslation('masters')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const isEdit = zone !== null
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ZoneFormValues>({
    defaultValues: {
      code: zone?.code ?? '',
      name: zone?.name ?? '',
      description: zone?.description ?? '',
    },
  })

  async function onSubmit(values: ZoneFormValues) {
    setFormError(null)
    const request: ZoneRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      description: values.description.trim() || null,
    }

    try {
      if (isEdit) {
        await updateZone(companyId, zone.id, request)
      } else {
        await createZone(companyId, request)
      }
      onSaved()
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('zones.form.edit') : t('zones.form.create')}
      subtitle={t('zones.form.subtitle')}
      size="md"
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

        <div className="row">
          <div className="col-12 col-md-4">
            <FormField label={tc('columns.code')} htmlFor="zone-code" error={errors.code?.message} required>
              <input
                id="zone-code"
                className={`form-control${errors.code ? ' is-invalid' : ''}`}
                {...register('code', {
                  required: tv('required'),
                  maxLength: { value: 32, message: tv('maxLength', { count: 32 }) },
                  pattern: { value: CODE_PATTERN, message: tv('codePattern') },
                })}
              />
            </FormField>
          </div>
          <div className="col-12 col-md-8">
            <FormField label={tc('columns.name')} htmlFor="zone-name" error={errors.name?.message} required>
              <input
                id="zone-name"
                className={`form-control${errors.name ? ' is-invalid' : ''}`}
                {...register('name', {
                  required: tv('required'),
                  maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
                })}
              />
            </FormField>
          </div>
        </div>

        <FormField label={tc('columns.description')} htmlFor="zone-description" error={errors.description?.message}>
          <textarea
            id="zone-description"
            rows={3}
            className={`form-control${errors.description ? ' is-invalid' : ''}`}
            {...register('description', { maxLength: { value: 1000, message: tv('maxLength', { count: 1000 }) } })}
          />
        </FormField>
      </form>
    </TmsDrawer>
  )
}
