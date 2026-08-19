package com.ebim.tms.iam.application;

import java.util.List;

/**
 * Everything the frontend needs immediately after sign-in: who the user is, which companies
 * they may select, and what they may do in each.
 *
 * <p>One call rather than one per company switch. The payload stays small - a handful of
 * companies and a few dozen permission codes each - and it removes a round trip from the most
 * latency-visible moment in the application.
 */
public record MeView(UserView user, List<CompanyAccessView> companies) {}
