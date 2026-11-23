# ADR-030 - AI assistants behind a port, starting with the route master's stop order

- **Status:** Accepted and implemented (no migration)
- **Date:** 2026-11-16
- **Relates to:** ADR-001 (React -> Spring Boot -> PostgreSQL), ADR-005 (tenant RLS for `tms_app`),
  ADR-010 (routing port and its estimates), ADR-011 (stop ETA; route optimisation deferred).
- **Supersedes in part:** the "Deferred by decision" entry for route optimisation in `CLAUDE.md`, for
  the route master only (section 6).

## Context

The business owner asked for two AI assistants using an Anthropic API key. The first one, built
here: in **Maestros > Rutas**, while a route is being created or edited, a button **«Optimizar
paradas»** sends the stops with their coordinates to an AI model, which proposes the order that
drives the fewest kilometres. The second one, for **Planificación**, comes later and is out of scope.

Two standing rules collide with the request:

1. Route optimisation - choosing the sequence rather than scheduling it - is "deferred by decision"
   (`CLAUDE.md`, ADR-011), to be lifted only by "a concrete requirement and an ADR".
2. Business operations flow React -> Spring Boot -> PostgreSQL; the browser holds no secret.

The requirement is now concrete. This ADR states how the feature lives with the rest of the rules.

## Decision

### 1. A new `ai` module answers ports; nothing else knows a vendor

- `com.ebim.tms.ai` is a business module in `ModuleBoundaryTest`. It owns the Anthropic SDK
  (`com.anthropic:anthropic-java`), the key, the prompts and the translation of an answer into plain
  values.
- The first port is `shared.reference.StopSequenceAdvisorPort`, implemented by
  `ai.infrastructure.AnthropicStopSequenceAdvisor`. `masterdata` asks it and never imports `ai`.
- `ModuleBoundaryTest.anthropic_sdk_stays_inside_the_ai_module` forbids `com.anthropic` anywhere
  else. Changing the model, the vendor or the SDK version is a change to one package.
- The Planificación assistant will be another port answered by the same module.

### 2. Configuration: off unless a key is set

`tms.ai.anthropic.*`, declared in `application-local.yml` and `application-prod.yml` (with no block -
the `test` profile - every value takes the default below from `AnthropicProperties`):

| Property | Env var | Default |
|---|---|---|
| `api-key` | `ANTHROPIC_API_KEY` | empty - assistants **off** |
| `model` | `TMS_AI_ANTHROPIC_MODEL` | `claude-opus-5-5` |
| `effort` | `TMS_AI_ANTHROPIC_EFFORT` | `medium` (low, medium, high, xhigh, max) |
| `timeout` | `TMS_AI_ANTHROPIC_TIMEOUT` | `90s` per attempt |
| `max-retries` | `TMS_AI_ANTHROPIC_MAX_RETRIES` | `1` |
| `max-tokens` | `TMS_AI_ANTHROPIC_MAX_TOKENS` | `16000` |

With no key the port reports itself unavailable and the endpoint answers
`503 feature-not-configured`; nothing is sent anywhere. The key lives in the backend's environment
(git-ignored `.env` locally, the hosting dashboard elsewhere), is never logged
(`AnthropicProperties.toString()` redacts it) and never reaches the browser. An effort the API does
not know fails at startup, not on the first click.

### 3. Least data out: geometry only

The question carries the origin and the stops as **numbered coordinates** plus the distance matrix,
and nothing else: no location name, code, address, customer, order, company or id leaves TMS. An
external provider learns where some points are, not whose they are. The model's explanation names
stops by their number in the planner's current list ("parada 3"), which the form can show as is.

### 4. The model proposes; TMS measures; a person decides

- `POST /api/v1/masterdata/routes/optimize-stops` takes `{originId, destinationIds}` - the form's
  current state, so it works on a route that is not saved yet - and **writes nothing**.
- Permission: `masterdata.route:manage`. It exists to edit a route and each call costs an AI request.
- The service resolves the locations in the caller's company (one short read-only transaction),
  refuses missing coordinates with `400 missing-coordinates` naming the codes, builds the N x N with
  `RoutingPort` (ADR-010) - the distances the rest of TMS charges a trip with - and then asks the
  model **outside any transaction**, so a slow answer never holds a database connection.
- The answer must name every stop exactly once; anything else is `502 ai-unavailable` and is never
  shown. Both the current and the proposed order are measured with the same matrix (origin to last
  stop, no return leg) and returned with `improved` and `estimated` flags.
- A shorter proposal is applied to the form's list as soon as it arrives, keeping each stop's
  service-time override, and a banner says so - «Orden propuesto», the kilometres before and
  after, and a note that the distances are estimates when they are. **«Descartar»** puts the stops
  back in the order they had. The route changes only when the planner saves it. A proposal that is
  not shorter reorders nothing and says so. If the planner edits the stops or the origin while the
  model is thinking, the late answer is dropped; if they edit them afterwards, the banner goes away.

### 5. How the model is called

- One Messages API call with **structured output** (a JSON schema derived from `SequenceAnswer`:
  `sequence`, `rationale`) and adaptive thinking at the configured effort. No tool loop: the whole
  problem fits in one message, and a loop would add turns, latency and cost to reach the same place.
- The matrix is part of the message so the model optimises the distances TMS will use, not straight
  lines; coordinates are there to show it the geography.
- The request opts into server-side refusal fallbacks (`anthropic-beta:
  server-side-fallback-2026-07-01`, `fallbacks: "default"`). A question made of numbers should never
  be declined; this is insurance. A refusal, a `max_tokens` stop, a rate limit, a rejected key, a 5xx,
  a timeout or an unparseable answer all become `502 ai-unavailable`; the cause goes to the log
  without the key or the coordinates.
- At most 40 stops per request (`RouteStopOptimizationService.MAX_STOPS`).

### 6. What stays deferred

This lifts the deferral for **one** thing: an advisory stop order on the **route master**, applied by
a person. Still deferred: optimising trips or planning runs, choosing vehicles, any automatic
re-sequencing, OR-Tools or any solver, and anything an AI writes without a person applying it. The
Planificación assistant needs its own section here (or its own ADR) before it is built.

## Consequences

- A new runtime dependency (`anthropic-java` 3.3.0 with OkHttp and the Kotlin stdlib); Jackson 2 was
  already on the classpath through Flyway.
- Cost and latency per click: one model call, typically seconds to tens of seconds; the worst case is
  `timeout x (max-retries + 1)`. There is no per-company quota yet; if usage grows, add one here.
- Tests never call Anthropic: the integration test replaces the port with a controllable fake; the
  adapter's unit test covers the message (numbers only), the configuration and the unconfigured path.
- The model can be wrong; the planner sees both totals and decides. Distances are estimates while no
  routing provider is configured (ADR-010), and the form says so.

## Alternatives considered

- **A deterministic heuristic (nearest neighbour + 2-opt) instead of a model.** Cheaper, faster and
  reproducible, and it may be the right tool for the Planificación assistant at scale. Not chosen
  because the requirement is an AI assistant; the port leaves room to put one behind the same
  contract, or to give the model a heuristic's answer as a starting point.
- **Calling Anthropic from the browser.** Rejected: it exposes the key and breaks ADR-001.
- **An agent loop with a "measure this sequence" tool.** Possibly better answers for large routes,
  at several times the latency and cost. Revisit with evidence, not before.
