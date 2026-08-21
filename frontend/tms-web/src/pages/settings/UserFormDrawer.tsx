import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import {
  inviteUser,
  updateUserProfile,
  updateUserRoles,
  type AdministeredUserView,
  type RoleView,
} from '../../shared/api/administrationApi'
import { applyApiFieldErrors } from '../../shared/api/formErrors'
import type { ApiError } from '../../shared/api/httpClient'
import { FormField } from '../../shared/ui/components/FormField'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const FORM_ID = 'user-access-form'

interface UserFormValues {
  email: string
  fullName: string
}

const KNOWN_FIELDS = new Set<keyof UserFormValues>(['email', 'fullName'])

interface UserFormDrawerProps {
  companyId: string
  /** `null` invites somebody new; otherwise the drawer edits this membership. */
  user: AdministeredUserView | null
  roles: RoleView[]
  /** The signed-in administrator's own `appUserId`, so the form can refuse to edit its own roles. */
  currentAppUserId: string | null
  onClose: () => void
  onSaved: () => void
}

/**
 * Invite somebody, or change what they may do here.
 *
 * Roles are checkboxes and not a dropdown because a membership holds several: a planner who is
 * also the person who maintains the fleet is one membership with two roles, and forcing that into
 * one choice is what makes people create a second account.
 *
 * A role the backend marks `assignable: false` is shown disabled with the reason rather than
 * hidden. An ORGANIZATION-scoped role on a company membership grants nothing at all, and an
 * administrator who cannot find ORGANIZATION_ADMIN in the list would reasonably conclude the
 * screen is broken; seeing it greyed out with "organization-wide" beside it answers the question.
 */
export function UserFormDrawer({
  companyId,
  user,
  roles,
  currentAppUserId,
  onClose,
  onSaved,
}: UserFormDrawerProps) {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const isEdit = user !== null
  const isSelf = isEdit && user.appUserId === currentAppUserId
  const [formError, setFormError] = useState<string | null>(null)
  const [selectedRoles, setSelectedRoles] = useState<string[]>(user?.roleCodes ?? [])
  const [rolesTouched, setRolesTouched] = useState(false)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<UserFormValues>({
    defaultValues: { email: user?.email ?? '', fullName: user?.fullName ?? '' },
  })

  function toggleRole(code: string) {
    setRolesTouched(true)
    setSelectedRoles((current) =>
      current.includes(code) ? current.filter((held) => held !== code) : [...current, code],
    )
  }

  async function onSubmit(values: UserFormValues) {
    setFormError(null)
    if (selectedRoles.length === 0) {
      setFormError(t('users.roleRequired'))
      return
    }

    try {
      if (!isEdit) {
        await inviteUser(companyId, {
          email: values.email.trim().toLowerCase(),
          fullName: values.fullName.trim(),
          roleCodes: selectedRoles,
        })
      } else {
        // Two calls because they are two permissions server-side: correcting a name is
        // `iam.user:manage` and changing what somebody may do is `iam.membership:manage`. Each is
        // sent only when it actually changed, so an administrator who holds one of the two is not
        // refused for the half they did not touch.
        if (values.fullName.trim() !== user.fullName) {
          await updateUserProfile(companyId, user.membershipId, values.fullName.trim())
        }
        if (rolesTouched && !isSelf) {
          await updateUserRoles(companyId, user.membershipId, selectedRoles)
        }
      }
      onSaved()
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, tv('highlightedFields')))
    }
  }

  return (
    <TmsDrawer
      open
      title={isEdit ? t('users.form.edit') : t('users.form.invite')}
      subtitle={isEdit ? t('users.form.editSubtitle') : t('users.form.inviteSubtitle')}
      size="md"
      onClose={onClose}
      dirty={isDirty || rolesTouched}
      closeOnEscape={!isSubmitting}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={isSubmitting}>
            {tc('actions.cancel')}
          </button>
          <button type="submit" form={FORM_ID} className="btn btn-primary" disabled={isSubmitting}>
            {isSubmitting ? tc('actions.saving') : isEdit ? tc('actions.save') : t('users.form.sendInvite')}
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

        <FormField
          label={tc('fields.email')}
          htmlFor="user-email"
          error={errors.email?.message}
          help={isEdit ? t('users.emailImmutable') : t('users.emailHelp')}
          required
        >
          <input
            id="user-email"
            type="email"
            className={`form-control${errors.email ? ' is-invalid' : ''}`}
            readOnly={isEdit}
            disabled={isEdit}
            {...register('email', {
              required: tv('required'),
              maxLength: { value: 254, message: tv('maxLength', { count: 254 }) },
            })}
          />
        </FormField>

        <FormField label={t('users.fullName')} htmlFor="user-full-name" error={errors.fullName?.message} required>
          <input
            id="user-full-name"
            className={`form-control${errors.fullName ? ' is-invalid' : ''}`}
            {...register('fullName', {
              required: tv('required'),
              maxLength: { value: 200, message: tv('maxLength', { count: 200 }) },
            })}
          />
        </FormField>

        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{t('users.roles')}</legend>
          {isSelf && (
            <div className="alert alert-warning py-2 small" role="alert">
              {t('users.selfRolesLocked')}
            </div>
          )}
          {roles.map((role) => {
            const checked = selectedRoles.includes(role.code)
            const locked = !role.assignable || isSelf
            return (
              <div className="form-check" key={role.code}>
                <input
                  className="form-check-input"
                  type="checkbox"
                  id={`user-role-${role.code}`}
                  checked={checked}
                  disabled={locked}
                  onChange={() => toggleRole(role.code)}
                />
                <label className="form-check-label" htmlFor={`user-role-${role.code}`}>
                  <span className="fw-semibold">{role.name}</span>
                  {!role.assignable && (
                    <span className="text-body-secondary"> — {t('users.organizationScoped')}</span>
                  )}
                  {role.description && <span className="d-block small text-body-secondary">{role.description}</span>}
                  <span className="d-block small text-body-secondary">
                    {t('users.permissionCount', { count: role.permissionCodes.length })}
                  </span>
                </label>
              </div>
            )
          })}
        </fieldset>
      </form>
    </TmsDrawer>
  )
}
