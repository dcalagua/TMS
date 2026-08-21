# What TMS tells you about itself

There is no observability stack here - no Prometheus, no Grafana, no OpenTelemetry, no log
shipper. That is a decision, not an omission: at this size the questions an operator actually asks
are "is it up", "which request was that", and "did the integration work", and all three are
answerable from what a Spring Boot application already exposes. This page is the record of what
exists so that the next person does not install a stack to get something they already have.

## Health

    GET /actuator/health          UP or DOWN, and nothing else
    GET /actuator/health/liveness
    GET /actuator/health/readiness

`show-details: never` in `application.yml` is load-bearing. The component detail Spring would
otherwise render names the database URL, the disk paths and the vendor versions, and this endpoint
is the one a load balancer reaches without a token. A probe needs one word; anything more is a
description of the deployment served to whoever asks.

`when-authorized` is used on the `local` profile, where the detail is useful and the audience is
the developer.

## Build identification

    GET /actuator/info

`management.info.env.enabled` is `false`, deliberately. Leaving it on publishes every `info.*`
property, and "put the version in `info.version`" is one careless commit away from "put the
connection string in `info.datasource`".

## Metrics

    GET /actuator/metrics
    GET /actuator/metrics/{name}

Values only - the exposure list is `health,info,metrics` and nothing else. There is no `beans`,
`env`, `configprops` or `mappings`: those describe the running configuration, and none of them
answers an operational question this product has.

Beside the JVM and HTTP defaults, the application counts the things whose absence is the symptom:

| Metric | Tags | The question it answers |
|---|---|---|
| `tms.audit.events` | `aggregateType`, `action` | Is business activity being recorded, and of what kind - without querying `tms.audit_event` itself |
| `tms.integration.requests` | outcome | Are partners' calls succeeding |
| `tms.integration.webhooks` | outcome | Are outbound deliveries landing, or is an endpoint dead |
| `tms.notification.raised`, `tms.notification.suppressed` | type | Is the alert board working, and is it being flooded |
| `tms.tracking.positions` | outcome | Is a feed reporting, and how much of it is being thinned |

Counters rather than gauges throughout: what matters is that the number moves.

## Correlation

Every request is stamped with a correlation id (`CorrelationIdFilter`), which appears in three
places that must agree:

- **Every log line**, through the pattern `%5p [%X{correlationId:-no-correlation-id}]`;
- **The error document** the caller received, as `correlationId` in the RFC 9457 body;
- **The audit entry**, as `tms.audit_event.correlation_id`.

That is what makes "a user says something failed at 14:32" a searchable question rather than a
guess. The audit screen filters on it directly (Seguridad → Auditoría), so one id gives you every
business change made while serving that request.

Integration requests carry it too (`IntegrationRequest.correlationId`), so a partner's failed call
can be traced from their side of the conversation.

## Errors

`server.error.include-message`, `include-stacktrace` and `include-binding-errors` are all `never`.
Callers get a typed problem code, a safe sentence, and the correlation id; the detail is in the
log, where it is reachable by whoever is allowed to read logs and by nobody else.

## What is deliberately not here

- **A metrics scrape target.** `metrics` is the Actuator endpoint, not a Prometheus exposition.
  Adding `micrometer-registry-prometheus` is a one-line dependency when there is something to
  scrape it - a deployment, not a laptop.
- **Distributed tracing.** One process, one database. A trace would show a request calling itself.
- **Structured (JSON) log output.** Worth it when a log aggregator is parsing them; today they are
  read by a human in a terminal, where the correlation id in a fixed column is easier.

Each of these becomes worth doing on the day there is a deployment to do it for. None of them is
blocked by anything in the code.
