# Platform provisioning — security

## 1. Three credentials that cannot become one another

TMS accepts three kinds of caller, on three chains that do not overlap:

| Chain | Path | Credential | Principal | Order |
|---|---|---|---|---|
| Control plane | `/internal/platform/**` | MasterAdmin ES256 JWT | `PlatformAuthentication` — no company, scopes as authorities | `HIGHEST_PRECEDENCE + 2` |
| Partner | `/integration/**` | integration client credentials | `MachineAuthentication` — company from the credential | `HIGHEST_PRECEDENCE + 1` |
| Human | everything else | Supabase JWT (JWKS) | `TmsAuthenticationToken` → `app_user` → membership → `CompanyScope` | catch-all, last |

A control-plane token is not a person and is not a partner: `PlatformAuthentication` is neither
`TmsAuthenticationToken` nor `CompanyScopedAuthentication`, so no code path can resolve it to an
`app_user` or hand it a company scope. Proven, not asserted — `PlatformProvisioningApiIntegrationTest`
sends a control-plane token to `/api/v1/me` and to `/integration/v1/identity/ping` and gets 401 from
both, and sends a human-shaped token and a legacy `Basic` credential to the provisioning endpoint and
gets 401 from both.

The provisioning chain disables CORS and anonymous access. It is called by a server; there is no
browser origin to allow, and no unauthenticated caller who should reach a controller.

## 2. What the token must satisfy

Verified in this order, each with its own answer:

| Check | Refusal |
|---|---|
| parses as a JWS (an unsecured `alg=none` JWT is not one) | `invalid-m2m-token` |
| `alg` is exactly `ES256` | `invalid-m2m-token` |
| signature verifies against the configured public key | `invalid-m2m-token` |
| `iss` = `masteradmin.ebim` | `invalid-issuer` |
| `aud` contains `tms.ebim` | `invalid-audience` |
| `sub` present | `invalid-m2m-token` |
| `jti` present | `invalid-m2m-token` |
| `iat` and `exp` present, `exp - iat` ≤ 300s | `invalid-m2m-token` |
| not expired (30s skew) | `invalid-m2m-token` |
| `scope` present | `invalid-m2m-token` |
| scope grants the operation | `missing-scope` (403) |

**Why `alg` is pinned rather than "any asymmetric algorithm".** Accepting `HS256` would let the
*public* key be used as a shared secret: anyone holding it — and it is public — could mint tokens TMS
would verify. `alg=none` is refused a step earlier, because an unsecured JWT never reaches a
signature check at all.

**There is no JWKS URL.** The key is configured as a value. A deployment that could be pointed at a
different key-serving URL could be pointed at an attacker's.

`actor_id` and `actor_role` are read, recorded on the provisioning row, and **authorise nothing**.

## 3. The key

TMS is given the **public** half and nothing else. The private key exists only on MasterAdmin and is
never requested, never stored here and never needed to run any test — the test suite generates
throw-away ES256 keys.

The verifier refuses a configured value that contains `PRIVATE KEY`. It would have worked, since an
EC private key encodes its public half; that is precisely why it must fail loudly instead.

**The PEM is never committed.** `C:\EBIM\keys\tms\qas\masteradmin-public.pem` is a local convenience
for producing the base64 value, and has no meaning on a server.

### Local: turn the PEM into the value

```powershell
$PublicKeyPath = "C:\EBIM\keys\tms\qas\masteradmin-public.pem"
$PublicKeyB64  = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($PublicKeyPath))
$env:EBIM_MASTERADMIN_M2M_PUBLIC_KEY_B64 = $PublicKeyB64
$env:EBIM_MASTERADMIN_M2M_ENABLED = "true"
```

Check it first — it must print a public key and contain no private material:

```powershell
Test-Path "C:\EBIM\keys\tms\qas\masteradmin-public.pem"      # True
Get-Content "C:\EBIM\keys\tms\qas\masteradmin-public.pem"    # -----BEGIN PUBLIC KEY-----
```

### QAS: the value is a secret, not a path

Set `EBIM_MASTERADMIN_M2M_PUBLIC_KEY_B64` as an environment variable / secret on the backend service.
Do not reference `C:\...` from a server, and do not put the PEM in the repository.

## 4. Configuration, and the name mapping

The brief's variable names are the operational interface; the Java side keeps this product's own
property namespace. `application.yml` binds one to the other.

| Environment variable | Property | Default |
|---|---|---|
| `EBIM_MASTERADMIN_M2M_ENABLED` | `tms.platform.m2m.enabled` | `false` |
| `EBIM_MASTERADMIN_M2M_ISSUER` | `tms.platform.m2m.issuer` | `masteradmin.ebim` |
| `EBIM_MASTERADMIN_M2M_AUDIENCE` | `tms.platform.m2m.audience` | `tms.ebim` |
| `EBIM_MASTERADMIN_M2M_ALGORITHM` | `tms.platform.m2m.algorithm` | `ES256` |
| `EBIM_MASTERADMIN_M2M_MAX_TOKEN_LIFETIME` | `tms.platform.m2m.max-token-lifetime` | `300s` |
| `EBIM_MASTERADMIN_M2M_CREATE_SCOPE` | `tms.platform.m2m.create-scope` | `tms:tenant:create` |
| `EBIM_MASTERADMIN_M2M_READ_SCOPE` | `tms.platform.m2m.read-scope` | `tms:tenant:read` |
| `EBIM_MASTERADMIN_M2M_PUBLIC_KEY_B64` | `tms.platform.m2m.public-key-base64` | none |
| `TMS_PLATFORM_RETAIN_REQUEST_SNAPSHOT` | `tms.platform.provisioning.retain-request-snapshot` | `false` |

**Disabled by default.** A deployment that has not been given a key answers `503
feature-not-configured` rather than looking like one that accepts provisioning calls.

Renaming a scope works: the filter grants the canonical authority when the token carries the
configured scope, so the annotations keep matching. Renaming cannot widen anything — a scope the
token does not carry produces no authority.

## 5. Database posture

Provisioning writes on the owner connection, because it runs before any company exists and therefore
outside any company scope. `tms.platform_tenant` and `tms.platform_provisioning` enable RLS and carry
the `p_backend_managed` policy the identity tables use; `tms_app` is granted `SELECT`, `INSERT` and
`UPDATE`, and `DELETE` is revoked — provisioning never deletes, so a tenant that was created stays
recorded and so does the call that created it.

MasterAdmin gets no database access of any kind: no connection string, no `service_role`, no Supabase
REST. The signed HTTP call is the entire interface.

## 6. What is not stored

The request body is not retained by default — only its SHA-256. It carries the administrator's email
and the company's registration data, and retaining personal data is an operator's decision
(`TMS_PLATFORM_RETAIN_REQUEST_SNAPSHOT`), not a default.

Error responses carry a machine code and a business sentence. Never a stack trace, never SQL, never a
constraint name. The cause goes to the log, behind the correlation id that is also on the row.
