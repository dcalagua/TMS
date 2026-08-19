import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <div className="text-center py-5">
      <p className="display-6 mb-2">404</p>
      <p className="text-body-secondary">This screen does not exist.</p>
      <Link className="btn btn-primary btn-sm" to="/">
        Back to dashboard
      </Link>
    </div>
  )
}
