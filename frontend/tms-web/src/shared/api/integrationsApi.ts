import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/**
 * The Integration Hub's two halves.
 *
 * Inbound: the machine credentials partners authenticate with, and the inbox of what they sent.
 * Outbound: the endpoints this company's events are pushed to, and the log of what was delivered.
 *
 * They are one screen because they are one question - "what is connected to us, and is it working" -
 * and two backend resources, with two permissions, because they fail in opposite directions: a
 * credential is a way in, a subscription is a way out.
 */

// ---------------------------------------------------------------------------
// Inbound: integration credentials
// ---------------------------------------------------------------------------

/** Mirrors the backend's `IntegrationClientView` (migrations V18, V31). */
export interface IntegrationClientView {
  id: string
  /** The public half of the credential. Not a secret; safe to show and to copy. */
  clientId: string
  name: string
  description: string | null
  /** `IntegrationScope` codes, e.g. `integration.order:write`. */
  scopes: string[]
  /** Set only on a credential holding `integration.tender:respond`. */
  carrierId: string | null
  active: boolean
  /** Best effort, written outside the request transaction. Answers "is this still in use". */
  lastUsedAt: string | null
  secretRotatedAt: string | null
  /** Non-null while a superseded secret is still accepted after a rotation. */
  rotationGraceEndsAt: string | null
  revokedAt: string | null
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `IntegrationClientSecretView`: the only response that carries a secret. */
export interface IntegrationClientSecretView {
  client: IntegrationClientView
  clientId: string
  secret: string
  /** Ready to paste: `clientId.secret`, which is what the partner configures. */
  bearerToken: string
  previousSecretValidUntil: string | null
  notice: string
}

export interface IntegrationClientRequest {
  name: string
  description?: string | null
  scopes: string[]
  carrierId?: string | null
}

/** Mirrors the backend's `IntegrationRequestView`: one row of the inbound inbox. */
export interface IntegrationRequestView {
  id: string
  integrationClientId: string
  operation: string
  idempotencyKey: string | null
  externalSystem: string | null
  externalReference: string | null
  status: 'SUCCEEDED' | 'PARTIAL' | 'REJECTED' | 'FAILED'
  httpStatus: number
  itemCount: number
  succeededCount: number
  failedCount: number
  resourceId: string | null
  errorSummary: string | null
  correlationId: string | null
  receivedAt: string
  completedAt: string
  durationMs: number
}

/**
 * A type alias and not an `interface`, deliberately. `apiRequest` takes its query as a
 * `Record<string, …>`, and TypeScript gives an object type alias an implicit index signature but
 * never gives one to an interface - so declaring this as an interface makes every call that
 * passes it as a query a compile error, which is what it was doing.
 */
export type PageParams = {
  page?: number
  size?: number
  sort?: string
}

export function fetchIntegrationClients(
  companyId: string,
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<PageResponse<IntegrationClientView>> {
  return apiRequest<PageResponse<IntegrationClientView>>('/integration-clients', {
    companyId,
    signal,
    query: params,
  })
}

export function createIntegrationClient(
  companyId: string,
  request: IntegrationClientRequest,
): Promise<IntegrationClientSecretView> {
  return apiRequest<IntegrationClientSecretView>('/integration-clients', {
    method: 'POST',
    companyId,
    body: request,
  })
}

export function updateIntegrationClient(
  companyId: string,
  id: string,
  request: IntegrationClientRequest,
): Promise<IntegrationClientView> {
  return apiRequest<IntegrationClientView>(`/integration-clients/${id}`, {
    method: 'PUT',
    companyId,
    body: request,
  })
}

/**
 * `graceHours` of 0 revokes the superseded secret at once, which is the right choice when it may
 * have leaked. Omitting it uses the deployment's configured window.
 */
export function rotateIntegrationClient(
  companyId: string,
  id: string,
  graceHours?: number,
): Promise<IntegrationClientSecretView> {
  return apiRequest<IntegrationClientSecretView>(`/integration-clients/${id}/rotate`, {
    method: 'POST',
    companyId,
    query: graceHours === undefined ? undefined : { graceHours },
  })
}

export function revokeIntegrationClient(companyId: string, id: string): Promise<IntegrationClientView> {
  return apiRequest<IntegrationClientView>(`/integration-clients/${id}/revoke`, { method: 'POST', companyId })
}

export function fetchIntegrationRequests(
  companyId: string,
  params: PageParams & { clientId?: string } = {},
  signal?: AbortSignal,
): Promise<PageResponse<IntegrationRequestView>> {
  return apiRequest<PageResponse<IntegrationRequestView>>('/integration-clients/requests', {
    companyId,
    signal,
    query: params,
  })
}

// ---------------------------------------------------------------------------
// Outbound: webhook subscriptions
// ---------------------------------------------------------------------------

/** Mirrors the backend's `WebhookSubscriptionView` (migration V35). */
export interface WebhookSubscriptionView {
  id: string
  name: string
  description: string | null
  targetUrl: string
  /** `WebhookEventType` names, the same vocabulary as the polling change feed. */
  eventTypes: string[]
  active: boolean
  /** Non-null only when TMS itself switched the endpoint off after repeated failures. */
  suspendedReason: string | null
  /** The last four characters of the signing secret - enough to recognise, not enough to sign. */
  secretHint: string
  secretRotatedAt: string | null
  /** The current streak, not a lifetime count. Zero the moment anything is delivered. */
  consecutiveFailures: number
  lastSuccessAt: string | null
  lastFailureAt: string | null
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `WebhookSubscriptionSecretView`. Shown once and never recoverable. */
export interface WebhookSubscriptionSecretView {
  subscription: WebhookSubscriptionView
  secret: string
  signatureHeader: string
  signedPayloadFormat: string
  notice: string
}

export interface WebhookSubscriptionRequest {
  name: string
  description?: string | null
  targetUrl: string
  eventTypes: string[]
}

export type WebhookDeliveryStatus = 'PENDING' | 'PROCESSED' | 'FAILED'

/** Mirrors the backend's `WebhookDeliveryView`. */
export interface WebhookDeliveryView {
  id: string
  subscriptionId: string
  subscriptionName: string
  /** The value the receiver deduplicates on. Stable across every attempt and redelivery. */
  eventId: string
  eventType: string
  occurredAt: string
  status: WebhookDeliveryStatus
  attemptCount: number
  /** Meaningful only while the status is PENDING. */
  nextAttemptAt: string
  lastAttemptAt: string | null
  completedAt: string | null
  lastStatusCode: number | null
  lastError: string | null
  createdAt: string
}

export interface WebhookDeliveryAttemptView {
  id: string
  attemptNumber: number
  attemptedAt: string
  durationMs: number
  /** Null when the call never produced a response: a timeout, a refused connection, bad DNS. */
  statusCode: number | null
  outcome: 'DELIVERED' | 'RETRYABLE_FAILURE' | 'PERMANENT_FAILURE'
  error: string | null
}

/** Mirrors the backend's `WebhookDeliveryDetailView`: the screen a dispute is settled from. */
export interface WebhookDeliveryDetailView {
  delivery: WebhookDeliveryView
  /** The body as every attempt sent it, byte for byte. */
  payload: string
  attempts: WebhookDeliveryAttemptView[]
}

export function fetchWebhookEventTypes(companyId: string, signal?: AbortSignal): Promise<string[]> {
  return apiRequest<string[]>('/webhooks/event-types', { companyId, signal })
}

export function fetchWebhookSubscriptions(
  companyId: string,
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<PageResponse<WebhookSubscriptionView>> {
  return apiRequest<PageResponse<WebhookSubscriptionView>>('/webhooks', { companyId, signal, query: params })
}

export function createWebhookSubscription(
  companyId: string,
  request: WebhookSubscriptionRequest,
): Promise<WebhookSubscriptionSecretView> {
  return apiRequest<WebhookSubscriptionSecretView>('/webhooks', { method: 'POST', companyId, body: request })
}

export function updateWebhookSubscription(
  companyId: string,
  id: string,
  request: WebhookSubscriptionRequest,
): Promise<WebhookSubscriptionView> {
  return apiRequest<WebhookSubscriptionView>(`/webhooks/${id}`, { method: 'PUT', companyId, body: request })
}

export function rotateWebhookSecret(companyId: string, id: string): Promise<WebhookSubscriptionSecretView> {
  return apiRequest<WebhookSubscriptionSecretView>(`/webhooks/${id}/rotate-secret`, {
    method: 'POST',
    companyId,
  })
}

export function setWebhookSubscriptionActive(
  companyId: string,
  id: string,
  active: boolean,
): Promise<WebhookSubscriptionView> {
  return apiRequest<WebhookSubscriptionView>(`/webhooks/${id}/${active ? 'activate' : 'deactivate'}`, {
    method: 'POST',
    companyId,
  })
}

/**
 * Mirrors `IntegrationHealthView` (JOB 13): si las integraciones funcionan **ahora mismo**.
 *
 * Todo esto ya se podía averiguar paginando dos listas, que es justo el problema: quien abre la
 * pantalla después de una mala noche debería obtener una respuesta, no una búsqueda.
 *
 * Dos cifras pesan más que el resto, y las dos van de que la cola no avanza. Mil entregas
 * pendientes que se están enviando es sano; tres que esperan desde el martes no lo es, y un
 * contador solo no distingue una cosa de la otra — por eso `oldestPendingAt` va al lado.
 */
export interface IntegrationHealthView {
  deliveriesPending: number
  /** Cuándo se creó la más vieja que sigue esperando, o null si no espera ninguna. **La edad es la señal.** */
  oldestPendingAt: string | null
  /** Agotaron sus reintentos: no se enviarán salvo que alguien las reintente. Es una cola de trabajo, no una estadística. */
  deliveriesFailed: number
  deliveriesProcessed: number
  /**
   * Suscripciones apagadas que **siguen acumulando** entregas detrás.
   *
   * El modo de fallo que parece silencio: desactivar no descarta nada, así que un socio apagado
   * "una hora" durante una incidencia y nunca vuelto a encender no produce ningún error.
   */
  inactiveSubscriptionsWithBacklog: number
  /** El inicio de la ventana que cubren las cifras de entrada. */
  requestsSince: string
  requestsSucceeded: number
  requestsPartial: number
  /** Rechazadas por su contenido: el problema es del socio. */
  requestsRejected: number
  /** TMS no pudo procesarlas: **el problema es nuestro**, y es la cifra que hay que mirar. */
  requestsFailed: number
}

export function fetchIntegrationHealth(
  companyId: string, signal?: AbortSignal,
): Promise<IntegrationHealthView> {
  return apiRequest<IntegrationHealthView>('/webhooks/health', { companyId, signal })
}

export function fetchWebhookDeliveries(
  companyId: string,
  params: PageParams & { subscriptionId?: string; status?: WebhookDeliveryStatus } = {},
  signal?: AbortSignal,
): Promise<PageResponse<WebhookDeliveryView>> {
  return apiRequest<PageResponse<WebhookDeliveryView>>('/webhooks/deliveries', {
    companyId,
    signal,
    query: params,
  })
}

export function fetchWebhookDelivery(
  companyId: string,
  id: string,
  signal?: AbortSignal,
): Promise<WebhookDeliveryDetailView> {
  return apiRequest<WebhookDeliveryDetailView>(`/webhooks/deliveries/${id}`, { companyId, signal })
}

export function retryWebhookDelivery(companyId: string, id: string): Promise<WebhookDeliveryView> {
  return apiRequest<WebhookDeliveryView>(`/webhooks/deliveries/${id}/retry`, { method: 'POST', companyId })
}
