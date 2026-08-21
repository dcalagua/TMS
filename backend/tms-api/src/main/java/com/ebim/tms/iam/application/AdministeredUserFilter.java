package com.ebim.tms.iam.application;

/**
 * The filter half of the people list, bound from query parameters as a {@code @ModelAttribute} -
 * the same shape {@code DriverFilter} and every other list endpoint uses.
 *
 * <p>No {@code companyId}, here or anywhere: the tenant comes from the {@code X-Company-Id} header
 * and is validated against the caller's memberships before this object is read.
 *
 * @param search matched against the email and the full name, case-insensitively. One box rather
 *     than two: an administrator looking for a person types whichever of the two they remember.
 * @param active the membership's own flag. {@code null} shows both, which is what an administrator
 *     auditing "who has ever had access here" wants; the screen defaults it to {@code true}.
 */
public record AdministeredUserFilter(String search, Boolean active) {
}
