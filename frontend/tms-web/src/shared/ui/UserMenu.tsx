import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import { confirmDialog } from './components/ConfirmDialog'

/** Signed-in user menu: identity and the one place `signOut` is called from the shell. */
export function UserMenu() {
  const { t } = useTranslation('auth')
  const { user, signOut } = useAuth()

  async function handleSignOut() {
    const confirmed = await confirmDialog({
      title: t('signOut.title'),
      text: t('signOut.text'),
      confirmLabel: t('signOut.confirm'),
    })
    if (confirmed) {
      await signOut()
    }
  }

  return (
    <div className="dropdown">
      <button
        className="btn btn-sm btn-outline-light dropdown-toggle"
        type="button"
        data-bs-toggle="dropdown"
        aria-expanded="false"
      >
        {user?.email ?? t('account')}
      </button>
      <ul className="dropdown-menu dropdown-menu-end">
        <li>
          <button type="button" className="dropdown-item" onClick={() => void handleSignOut()}>
            {t('signOut.action')}
          </button>
        </li>
      </ul>
    </div>
  )
}
