import { useAuth } from '../auth/AuthContext'
import { confirmDialog } from './components/ConfirmDialog'

/** Signed-in user menu: identity and the one place `signOut` is called from the shell. */
export function UserMenu() {
  const { user, signOut } = useAuth()

  async function handleSignOut() {
    const confirmed = await confirmDialog({
      title: 'Sign out?',
      text: 'You will need to sign in again to continue.',
      confirmLabel: 'Sign out',
      cancelLabel: 'Cancel',
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
        {user?.email ?? 'Account'}
      </button>
      <ul className="dropdown-menu dropdown-menu-end">
        <li>
          <button type="button" className="dropdown-item" onClick={() => void handleSignOut()}>
            Sign out
          </button>
        </li>
      </ul>
    </div>
  )
}
