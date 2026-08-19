/**
 * Cross-cutting building blocks shared by every business module: API primitives,
 * configuration and security infrastructure.
 *
 * <p>Boundary rule: {@code shared} must never depend on a business module. Business
 * modules may depend on {@code shared}. Enforced by
 * {@code com.ebim.tms.architecture.ModuleBoundaryTest}.
 */
package com.ebim.tms.shared;
