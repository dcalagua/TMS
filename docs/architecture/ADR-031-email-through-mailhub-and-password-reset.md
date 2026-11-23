# ADR-031 - Email through EBIM MailHub, starting with the password reset

- **Status:** Accepted and implemented (no migration)
- **Date:** 2026-11-16
- **Relates to:** ADR-001 (React -> Spring Boot), ADR-012 (pre-provisioned users), ADR-020 (the
  backend's Supabase `service_role` key), ADR-029 (an anonymous, rate-limited endpoint).

## Context

EBIM sends its email through **MailHub**, a Supabase Edge Function of its own that renders templates
it holds and delivers them: `POST .../functions/v1/mailhub-send` with an `X-MailHub-Key` header, an
`x-region` header and `{to, content: {type: "template", templateKey, variables}, referenceId,
idempotencyKey}`.

Until now the only email TMS caused was Supabase Auth's own password reset, sent by Supabase with
its template. The business owner wants that email to come from MailHub, in EBIM's template
`restablecer-contrasena`, with the request driven by the TMS backend ("camino B": the backend asks
for the link and sends it; the Supabase Send Email Hook was the alternative considered).

## Decision

### 1. One port for template email; MailHub behind it

- `shared.reference.TemplateEmailPort` (`send(TemplateEmail)`, `isAvailable()`), implemented by
  `notification.infrastructure.MailHubEmailSender`. Any module may send a template; none knows the
  provider.
- `tms.mailhub.url`, `tms.mailhub.token` (`MAILHUB_TOKEN`), `tms.mailhub.region` (`us-east-1`), in
  `application-local.yml` and `application-prod.yml`. With no token the port is unavailable. The
  token is read from the environment and never logged (`MailHubProperties.toString()` redacts it).

### 2. The password reset ("camino B")

`POST /api/v1/auth/password-reset {email}`, anonymous:

1. Rate limited before anything is read: 10 requests per client address per 10 minutes, 3 per email
   address per hour (`429` with `Retry-After`).
2. Answers **`202` for every well-formed address**, whether or not it has an account, and does the
   rest on a background thread, so neither the status nor the response time says who has access.
   Only a deployment that cannot send at all answers otherwise (`503 feature-not-configured`, for
   every address alike).
3. In the background: find the active `tms.app_user` by email; ask Supabase Auth for a recovery
   link (`POST /auth/v1/admin/generate_link`, `type: recovery`, with the project's `service_role`
   key - it returns the link and sends nothing); send `restablecer-contrasena` through MailHub.
   Unknown, inactive or Auth-less addresses send nothing; every outcome is logged by user id, never
   by address.

| MailHub field | Value |
|---|---|
| `to` | `[app_user.email]` |
| `variables.nombre` | `app_user.full_name` |
| `variables.correo` | `app_user.email` |
| `variables.solicitadoEn` | request time, `dd/MM/yyyy HH:mm`, `tms.auth.password-reset.time-zone` |
| `variables.enlace` | Supabase's recovery link, landing on `tms.auth.password-reset.redirect-url` |
| `variables.vigencia` | `tms.auth.password-reset.link-validity` |
| `variables.producto` | `tms.auth.password-reset.product-name` (`TMS`) |
| `referenceId` | `app_user.id` |
| `idempotencyKey` | `restablecer-contrasena-{app_user.id}` |

The link lands on the existing `/reset-password` page, which is unchanged. The frontend's form now
calls the backend instead of `supabase.auth.resetPasswordForEmail`.

Configuration: `tms.auth.password-reset.redirect-url` (must be in the Supabase project's Auth
redirect allow-list), `link-validity` (must state the project's email OTP expiry - TMS does not set
it), `product-name`, `time-zone`; `tms.auth.supabase.url` and `service-key`, defaulting in `prod` to
the evidence store's (ADR-020).

### 3. The new user's invitation (amends ADR-012)

«Invitar» in Usuarios y accesos now finishes the job ADR-012 left to an operator: somebody with **no
sign-in account** - a new person, or a profile pre-provisioned before - gets the welcome email
`registro-usuario`, and their Supabase Auth account is created and linked to the profile.

1. The invitation saves the profile and membership as before. Only when that transaction commits,
   in the background: `generate_link` with `type: invite` (Auth creates the account and returns its
   link; when the account exists already, `422`, a `recovery` link to it instead), then
   `app_user.auth_user_id` is set to that account (only while it is null), then the email is sent.
2. **Only to people without an account.** Inviting somebody who already has one - they work for
   another company - grants access and sends nothing. The invite response says which
   (`accountLinked`), and the screen tells the administrator.
3. **«Reenviar invitación»** (`POST /admin/users/{membershipId}/resend-invitation`,
   `iam.membership:manage`, `202`) sends it again with a fresh link: refused for an
   organization-wide row, a revoked access or a disabled account; 5 per person per hour.
4. If Auth or MailHub fail, the invitation stands and the list shows «Sin cuenta de acceso».

| MailHub field | Value |
|---|---|
| `to` | `[app_user.email]` |
| `variables.nombre` / `correo` | `app_user.full_name` / `app_user.email` |
| `variables.enlace` | the invitation (or recovery) link, landing on `tms.auth.password-reset.redirect-url` |
| `variables.vigencia` | `tms.auth.invitation.link-validity` - how long that link lasts |
| `variables.urlAcceso` | `tms.auth.invitation.access-url` |
| `variables.producto` | `TMS`, fixed |
| `referenceId` | `app_user.id` |
| `idempotencyKey` | `registro-usuario-{app_user.id}-{epoch millis}` - one per send, so a resend is a new email |

### 4. Security

`SecurityConfig` permits `POST /auth/password-reset` and nothing else new: the person cannot sign
in, which is the point. The `service_role` key and the MailHub token stay in the backend.

## Consequences

- **Idempotency key per user.** `restablecer-contrasena-{id}` is the same for every request of the
  same person. If MailHub keeps keys without expiry, a second request (the first link expired) is
  not delivered. This follows the brief; if MailHub deduplicates for longer than the link lives, the
  key must carry a request id (`...-{id}-{requestId}`).
- **`vigencia` is a statement, not a setting.** Supabase's default email OTP expiry is 1 hour; the
  email must say what the project actually uses.
- **Email scanners.** Supabase's link is single-use; a corporate scanner that opens links can spend
  it. Landing on a TMS page that verifies on a click (`verifyOtp` with the hashed token) would close
  that; not done yet.
- Tests use fakes for MailHub and Supabase Auth; nothing is sent from the suite.
- ADR-012's manual linking (`UPDATE ... FROM auth.users`) is no longer needed for people invited
  from Usuarios y accesos. MasterAdmin's pre-provisioned administrator still is linked by hand.
