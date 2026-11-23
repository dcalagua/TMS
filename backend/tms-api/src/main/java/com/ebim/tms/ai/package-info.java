/**
 * The AI assistants (ADR-030): the one place in TMS that talks to a large language model.
 *
 * <p>Today it answers one question, {@link com.ebim.tms.shared.reference.StopSequenceAdvisorPort}:
 * "in what order should a vehicle visit these stops", for the route master's "Optimizar paradas".
 * A planning assistant is the next one; it will be another port answered here.
 *
 * <h2>What this module owns</h2>
 *
 * <p>The Anthropic SDK, the API key, the prompts and the translation of a model's answer into a
 * port's plain values. Nothing else in TMS imports {@code com.anthropic}
 * ({@code ModuleBoundaryTest.anthropic_sdk_stays_inside_the_ai_module}), so changing the model, the
 * vendor or the prompt is a change to this package alone.
 *
 * <h2>The rules every assistant here follows</h2>
 *
 * <ul>
 *   <li><b>Off unless configured.</b> With no {@code tms.ai.anthropic.api-key} the ports report
 *       themselves unavailable and the endpoints answer {@code 503 feature-not-configured}.</li>
 *   <li><b>Advice, not decisions.</b> An answer is shown to a person, who applies it or not. No
 *       assistant writes to the database.</li>
 *   <li><b>Least data out.</b> A question carries what the model needs to answer it and no more -
 *       coordinates and distances, never names, customers or companies.</li>
 *   <li><b>The browser never sees the key.</b> React calls Spring Boot, Spring Boot calls the
 *       provider (ADR-001).</li>
 * </ul>
 */
package com.ebim.tms.ai;
