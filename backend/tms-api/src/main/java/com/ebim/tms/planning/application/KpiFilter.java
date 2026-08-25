package com.ebim.tms.planning.application;

import java.time.LocalDate;

/**
 * What span of operating days the KPI report is about, exactly as it arrived on the query string.
 *
 * <p>Company is deliberately absent, for the reason {@link ControlTowerFilter} gives: it comes from
 * the resolved {@code CompanyScope} and is never something a caller can widen.
 *
 * <p>Both ends may be null and both are resolved by {@link KpiRange}, which is where every rule
 * about them lives - the default span, the company's own today, the cap, and the refusal of a range
 * that runs backwards. Nothing here decides anything; a filter that quietly defaulted its own dates
 * would be a second opinion about what "the last thirty days" means, and the CSV export would have
 * to agree with it by coincidence.
 *
 * @param from the first operating day to include, inclusive, or null
 * @param to   the last operating day to include, inclusive, or null
 */
public record KpiFilter(LocalDate from, LocalDate to) {
}
